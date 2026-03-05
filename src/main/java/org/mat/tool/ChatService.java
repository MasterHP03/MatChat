package org.mat.tool;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.mat.def.ImageType;
import org.mat.def.Tools;
import org.mat.exception.NoResponseException;
import org.mat.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface between Discord and Gemini.
 * Actual call for Gemini occurs in GeminiManager.
 */
public class ChatService {
    private final DBManager db;
    private final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public ChatService(DBManager db) {
        this.db = db;
    }

    /**
     * Receives a message and prepares to send the context to Gemini.
     * @param sessionId ID of chat session.
     * @param message Message sent by user this turn.
     */
    public void processUserMessage(long sessionId, Message message) {
        List<Content> fullHistory = new ArrayList<>(db.getStructuredHistory(sessionId));

        fullHistory = parseImage(message.getJDA(), fullHistory);
        generateAndReply(sessionId, fullHistory, message);
    }

    /**
     * Rerolls the previous message.
     * @param sessionId ID of chat session.
     * @param message The reroll command message. Only for reply.
     */
    public void reroll(long sessionId, Message message) {
        DBManager.MessageInfo lastMsg = db.getLastMessageInfo(sessionId);
        if (lastMsg == null) {
            message.reply("마지막 메시지가 존재하지 않습니다.").queue();
            return;
        }

        // 봇의 대답일 경우 디스코드 및 DB에서 삭제
        if (lastMsg.role().equals("model")) {
            long lastMsgId = lastMsg.msgId();
            message.getChannel().deleteMessageById(lastMsgId).queue(
                    success -> logger.info("리롤로 인한 삭제: 성공, ID: {}", lastMsgId),
                    error -> logger.warn("리롤로 인한 삭제: 실패, ID: {}", lastMsgId)
            );
            db.deleteMessage(sessionId, lastMsgId);
        }

        // 마지막 메시지가 유저이므로, 추가 작업 없이 그대로 로드
        List<Content> fullHistory = db.getStructuredHistory(sessionId);
        if (fullHistory.isEmpty()) {
            message.reply("재생성할 대화 내역이 없습니다.").queue();
            return;
        }

        fullHistory = parseImage(message.getJDA(), fullHistory);
        generateAndReply(sessionId, fullHistory, message);
    }

    /**
     * Receives a response from Gemini and sends it to Discord.
     * If the response is too long, it slices it before sending.
     * @param sessionId ID of chat session.
     * @param fullHistory Full context of the session.
     * @param message User's current message (for reply).
     */
    public void generateAndReply(long sessionId, List<Content> fullHistory, Message message) {
        DBManager.SessionInfo info = db.getSessionInfo(sessionId);
        String systemPrompt = Config.getSystemInstruction(info.persona());

        try {
            message.getChannel().sendTyping().queue();

            // Gemini 호출
            GenerateContentResponse response = GeminiManager.generate(systemPrompt, fullHistory,
                    info.model(), info.userNote(), db.isToolEnabled(sessionId, Tools.IMAGE));

            Content responseContent = response.candidates().orElseThrow().getFirst().content().orElse(null);
            if (responseContent == null) throw new Exception("응답으로 반환된 Content가 null입니다.");
            List<Part> responseParts = responseContent.parts().orElse(new ArrayList<>());
            boolean isFunctionCall = false;
            FunctionCall funcCall = null;

            // 토큰 사용량 계산
            int[] tokens = getTokenCount(response);

            for (Part part : responseParts) {
                if (part.functionCall().isPresent()) {
                    isFunctionCall = true;
                    funcCall = part.functionCall().get();
                    break;
                }
            }

            if (isFunctionCall) {
                String funcName = funcCall.name().orElse("");

                if (Tools.IMAGE.getToolName().equals(funcName)) {
                    Map<String, Object> args = funcCall.args().orElse(new HashMap<>());
                    String imagePrompt = (String) args.get("prompt");
                    String spokenMessage = (String) args.get("message");

                    String preText = spokenMessage != null ? spokenMessage : "";

                    logger.info("이미지 생성 요청 감지. 프롬프트: {}", imagePrompt);
                    logger.info("봇의 메시지: {}", preText);

                    GenerateContentResponse funcResponse = GeminiManager.generateImage(imagePrompt);
                    List<Part> funcParts = funcResponse.candidates().orElseThrow()
                            .getFirst().content().orElseThrow().parts().orElseThrow();
                    StringBuilder postText = new StringBuilder();
                    byte[] bytes = null;
                    for (Part part : funcParts) {
                        if (part.text().isPresent()) {
                            postText.append("\n").append(part.text()).append("\n");
                        } else if (part.inlineData().isPresent()) {
                            bytes = part.inlineData().get().data().orElseThrow();
                        }
                    }
                    if (bytes == null) throw new RuntimeException("이미지 생성 결과가 null입니다.");
                    byte[] imageBytes = bytes;

                    int[] imageTokens = getTokenCount(funcResponse);

                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
                    String fileName = LocalDateTime.now().format(dtf) + ".png";

                    message.replyFiles(FileUpload.fromData(imageBytes, fileName))
                            .setContent(preText + postText)
                            .queue(botMsg -> {
                                db.addMessage(sessionId, botMsg.getIdLong(), "model",
                                        preText + postText, info.model(),
                                        tokens[0], tokens[1], tokens[2], tokens[3], tokens[4], tokens[5],
                                        imagePrompt, imageTokens[0], imageTokens[1], imageTokens[2],
                                        imageTokens[3], imageTokens[4], imageTokens[5]);

                                FileUtil.upload(message.getJDA(), imageBytes, fileName, sessionId)
                                        .thenAccept(result -> {
                                            if (result != null) {
                                                db.addAttachment(sessionId,
                                                        botMsg.getIdLong(),
                                                        ImageType.GENERATED.name(),
                                                        result.url(),
                                                        result.archiveMsgId());
                                            }
                                        });
                                }, e -> {
                                logger.error("이미지 전송 실패!");
                                throw new RuntimeException("이미지 전송 실패 ", e);
                            });
                }
                return;
            }

            String responseText = response.text() == null ? "응답 없음" : response.text();

            // 메시지 분할 전송
            int length = responseText.length();
            int count = 0;
            final int CHUNK_SIZE = Config.getChunkSize();
            boolean isFirstChunk = true;

            while (count < length) {
                final boolean finalIsFirstChunk = isFirstChunk;
                String splitText = responseText.substring(count, Math.min(count + CHUNK_SIZE, length));

                message.reply(splitText).queue(botMsg -> {
                    if (finalIsFirstChunk) {
                        // 메시지 전송 성공 시에만 DB에 현재 턴의 모든 내용 저장
                        // 유저 메시지는 MessageEvent에서 이미 저장함
                        // 청크 나눠보낼 때, 첫 청크를 보낼 때만 저장되도록
                        db.addMessage(sessionId, botMsg.getIdLong(), "model", responseText,
                                info.model(),
                                tokens[0], tokens[1], tokens[2], tokens[3], tokens[4], tokens[5]
                        );
                    }
                });
                isFirstChunk = false;
                count += CHUNK_SIZE;
            }
        } catch (Exception e) {
            handleException(message, e);
        }
    }

    /**
     * Sends the error message properly as a reply.
     * @param message User's input message that occurred the error.
     * @param e Exception object itself.
     */
    private void handleException(Message message, Exception e) {
        if (e instanceof ApiException ae) {
            message.reply(
                    String.join(" | ", "" + ae.code(), ae.status(), ae.message())
            ).queue();
        } else if (e instanceof NoResponseException) {
            message.reply(e.getMessage()).queue();
        } else {
            logger.error("응답 중 알 수 없는 오류: ", e);
            message.reply("알 수 없는 오류: " + e.getMessage()).queue();
        }
    }

    private List<Content> parseImage(JDA jda, List<Content> toParse) {
        List<Content> readyHistory = new ArrayList<>();

        for (Content c : toParse) {
            List<Part> readyParts = new ArrayList<>();

            for (Part p : c.parts().orElse(new ArrayList<>())) {
                String text = p.text().orElse(null);

                if (text != null && text.startsWith("[IMG:")) {
                    try {
                        // Getting rid of "[IMG:", "]", leaving "ID:URL" (https has colon, so proper is ID being front
                        String inner = text.substring(5, text.length() - 1);

                        int firstColon = inner.indexOf(":");
                        long archiveId = Long.parseLong(inner.substring(0, firstColon));
                        String url = inner.substring(firstColon + 1);

                        readyParts.add(toImagePart(jda, url, archiveId));
                    } catch (Exception e) {
                        logger.error("이미지 태그 파싱 중 에러 발생: {}", text, e);
                        readyParts.add(Part.fromText("[이미지 파싱 실패]"));
                    }
                } else {
                    // 일반 텍스트
                    readyParts.add(p);
                }
            }

            readyHistory.add(Content.builder()
                    .role(c.role().orElse(""))
                    .parts(readyParts)
                    .build());
        }

        return readyHistory;
    }

    private Part toImagePart(JDA jda, String oldUrl, long archiveMsgId) {
        HttpResponse<byte[]> response = download(oldUrl);
        String finalUrl = oldUrl;

        try {
            if (response == null || response.statusCode() == 403 || response.statusCode() == 404) {
                logger.warn("HTTP 링크 만료, 디스코드 접근 시도");

                TextChannel archive = jda.getTextChannelById(Config.getArchive());
                if (archive == null) {
                    throw new RuntimeException("유효한 채널이 아님 (ID 실수?)");
                }
                Message msg = archive.retrieveMessageById(archiveMsgId).complete();

                String freshUrl = msg.getAttachments().getFirst().getUrl();
                finalUrl = freshUrl;
                db.updateImageUrl(archiveMsgId, freshUrl);

                response = download(freshUrl);
                if (response == null || response.statusCode() != 200) {
                    throw new RuntimeException("새로 불러온 링크로도 다운로드 실패");
                }
            }
            byte[] imageBytes = response.body();
            String mimeType = finalUrl.contains(".png") ? "image/png" : "image/jpeg";
            return Part.fromBytes(imageBytes, mimeType);
        } catch (Exception e) {
            logger.error("디스코드 이미지 다운로드 실패", e);
            return Part.fromText("[이미지 로드 실패]");
        }
    }

    private HttpResponse<byte[]> download(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).build();

            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            logger.error("HTTP 이미지 다운로드 실패 ({})", imageUrl, e);
            return null;
        }
    }

    /**
     * Gets the information of token usage.
     * @param response A response to examine the token usage.
     * @return An integer array that contains the token usage.<br>
     * Order: Input - Cache - Tool - Thought - Output - Total
     */
    private int[] getTokenCount(GenerateContentResponse response) {
        int[] tokens = new int[6];
        if (response.usageMetadata().isPresent()) {
            GenerateContentResponseUsageMetadata usage = response.usageMetadata().get();
            tokens[0] = usage.promptTokenCount().orElse(0);
            tokens[1] = usage.cachedContentTokenCount().orElse(0);
            tokens[2] = usage.toolUsePromptTokenCount().orElse(0);
            tokens[3] = usage.thoughtsTokenCount().orElse(0);
            tokens[4] = usage.candidatesTokenCount().orElse(0);
            tokens[5] = usage.totalTokenCount().orElse(0);
        }
        return tokens;
    }
}
