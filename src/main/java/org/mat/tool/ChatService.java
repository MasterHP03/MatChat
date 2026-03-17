package org.mat.tool;

import com.google.genai.errors.ApiException;
import com.google.genai.types.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.FileUpload;
import org.mat.def.FileType;
import org.mat.def.GeminiModel;
import org.mat.def.Tools;
import org.mat.exception.NoResponseException;
import org.mat.util.FileUtil;
import org.mat.util.LatexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interface between Discord and Gemini.
 * Actual call for Gemini occurs in GeminiManager.
 */
public class ChatService {
    private final DBManager db;
    private final Logger logger = LoggerFactory.getLogger(ChatService.class);

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

        fullHistory = parseFiles(message.getJDA(), fullHistory);
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

        fullHistory = parseFiles(message.getJDA(), fullHistory);
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

        String currentTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String timeContext = """
                \n
                [System Note (Do not consider this as user input)]
                Current Time (GMT): %s
                """.formatted(currentTime);

        Content lastContent = fullHistory.getLast();
        List<Part> updatedParts = new ArrayList<>(lastContent.parts().orElse(new ArrayList<>()));
        updatedParts.add(Part.fromText(timeContext));
        Content updatedContent = Content.builder()
                .role(lastContent.role().orElse("user"))
                .parts(updatedParts)
                .build();
        fullHistory.set(fullHistory.size() - 1, updatedContent);

        try {
            message.getChannel().sendTyping().queue();

            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String thisMonth = today.substring(0, 7) + "-%"; // "2026-03-%"
            boolean isQueryWise = info.model().startsWith("gemini-3");

            // TODO Brave Search API Fallback Logic
            boolean userWantsSearch = checkSearchQuota(sessionId, info.model(), today, thisMonth);
            boolean userWantsImage = db.isToolEnabled(sessionId, Tools.IMAGE);

            boolean mainSearch = userWantsSearch;
            boolean mainImage = userWantsImage;

            // 아직은 내장 툴과 커스텀 Function이 공존할 수 없으니, 임시 라우팅
            if (mainSearch && mainImage) {
                String msgText = message.getContentRaw();
                if (msgText.contains("그려")) {
                    mainSearch = false;
                    logger.info("이미지 라우팅: 메인 모델의 검색을 임시로 끕니다.");
                } else {
                    mainImage = false;
                    logger.info("일반 라우팅: 메인 모델의 이미지 툴을 임시로 끕니다.");
                }
            }

            // Gemini 호출
            GenerateContentResponse response = GeminiManager.generate(systemPrompt, fullHistory,
                    info.model(), info.userNote(),
                    mainImage, mainSearch);

            List<Candidate> candidates = response.candidates().orElse(new ArrayList<>());
            Content responseContent = candidates.getFirst().content().orElseThrow(() ->
                    new Exception("응답으로 반환된 Content가 null입니다."));
            List<Part> responseParts = responseContent.parts().orElse(new ArrayList<>());

            // 토큰 사용량 계산
            int[] tokens = getTokenCount(response);

            // 메인 출처 리스트 추출
            String sourceText = extractSearchSources(candidates, mainSearch, isQueryWise,
                    today, info.model(), "답변 참고 자료:\n");

            // Function Call인지 아닌지 검사
            for (Part part : responseParts) {
                if (part.functionCall().isPresent()) {
                    FunctionCall funcCall = part.functionCall().get();
                    if (Tools.IMAGE.getToolName().equals(funcCall.name().orElse(""))) {
                        handleImageTool(sessionId, message, fullHistory, info, funcCall,
                                userWantsSearch, isQueryWise, today, tokens, sourceText);
                        return;
                    }
                }
            }

            // 텍스트면 출력 준비
            String responseText = response.text() == null ? "응답 없음" : response.text();
            String displayResponseText = responseText;

            // 수식 번역 파트
            Matcher matcher = Pattern.compile("\\$\\$(.*?)\\$\\$|\\\\\\((.*?)\\\\\\)", Pattern.DOTALL)
                    .matcher(displayResponseText);

            StringBuilder sb = new StringBuilder();
            List<String> formulas = new ArrayList<>();
            int mathCount = 1;

            while (matcher.find()) {
                String formula = matcher.group(1);
                if (formula == null) formula = matcher.group(2);
                formulas.add(formula.trim());
                matcher.appendReplacement(sb, "[수식" + mathCount + "]");
                mathCount++;
            }
            matcher.appendTail(sb);
            displayResponseText = sb.toString();

            FileUpload formulaFile = null;

            if (!formulas.isEmpty()) {
                logger.info("총 {}개의 수식 발견, 일괄 렌더링 시작", formulas.size());

                StringBuilder combinedFormula = new StringBuilder("\\begin{aligned}\n");
                for (int i = 0; i < formulas.size(); i++) {
                    combinedFormula.append("&\\text{[").append(i + 1).append("]} \\quad {")
                            .append(formulas.get(i)).append("}");
                    if (i < formulas.size() - 1) {
                        combinedFormula.append(" \\\\\n");
                    } else {
                        combinedFormula.append("\n");
                    }
                }
                combinedFormula.append("\\end{aligned}");

                String imageUrl = LatexUtil.renderToImageUrl(combinedFormula.toString());
                if (imageUrl != null) {
                    byte[] imageBytes = FileUtil.downloadBytes(imageUrl);
                    if (imageBytes != null) {
                        formulaFile = FileUpload.fromData(imageBytes, "formulas.png");
                        logger.info("수식 모음 이미지 생성 완료");
                    } else {
                        logger.warn("수식 이미지 다운로드 실패");
                        displayResponseText = responseText + "\n\n[수식 이미지 다운로드 실패]"; // 원본 텍스트라도 남아있게
                    }
                } else {
                    logger.warn("QuickLaTeX API 렌더링 실패");
                    displayResponseText = responseText + "\n\n[수식 이미지 서버 통신 실패]";
                }
            }

            displayResponseText += (sourceText.isBlank() ? "" : "\n\n" + sourceText);

            // 메시지 분할 전송
            sendMessageInChunks(message, displayResponseText, formulaFile, botMsg -> {
                // 메시지 전송 성공 시에만 DB에 현재 턴의 모든 내용 저장
                // 유저 메시지는 MessageEvent에서 이미 저장함
                // 청크 나눠보낼 때, 첫 청크를 보낼 때만 저장되도록
                db.addMessage(sessionId, botMsg.getIdLong(), "model", responseText,
                        info.model(), tokens[0], tokens[1], tokens[2],
                        tokens[3], tokens[4], tokens[5], sourceText
                );
            });
        } catch (Exception e) {
            handleException(message, e);
        }
    }

    private void sendMessageInChunks(Message message, String text, Consumer<Message> onFirstChunkSent) {
        sendMessageInChunks(message, text, null, onFirstChunkSent);
    }

    private void sendMessageInChunks(Message message, String text, FileUpload files, Consumer<Message> onFirstChunkSent) {
        final int CHUNK_SIZE = Config.getChunkSize();
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        String[] lines = text.split("\n");
        for (String line : lines) {
            // 한 줄이 2000자가 넘는 경우
            if (line.length() > CHUNK_SIZE) {
                // 만들던 청크는 정리하기
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
                // 큰 청크 분해
                int index = 0;
                while (index < line.length()) {
                    int nextIndex = Math.min(index + CHUNK_SIZE, line.length());
                    chunks.add(line.substring(index, nextIndex));
                    index = nextIndex;
                }
            } else {
                // 한 줄이 2000자가 넘지 않는 경우, 청크가 커지는 경우에만 새 청크 생성
                if (currentChunk.length() + line.length() + 1 > CHUNK_SIZE) {
                    chunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
                currentChunk.append(line).append("\n");
            }
        }
        if (!currentChunk.isEmpty()) chunks.add(currentChunk.toString()); // 나머지 텍스트
        if (chunks.isEmpty()) chunks.add(""); // 텍스트가 없을 때

        boolean isFirstChunk = true;
        for (String chunk : chunks) {
            final boolean finalIsFirstChunk = isFirstChunk;

            var action = message.reply(chunk);

            if (finalIsFirstChunk && files != null) {
                action = action.addFiles(files);
            }

            action.queue(botMsg -> {
                if (finalIsFirstChunk && onFirstChunkSent != null) {
                    onFirstChunkSent.accept(botMsg);
                }
            }, e -> {
                logger.error("분할 메시지 전송 실패", e);
                if (finalIsFirstChunk && files != null) throw new RuntimeException("이미지 전송 실패", e);
            });
            isFirstChunk = false;
        }
    }

    /**
     * Checks quota of Grounding with Google Search.
     */
    private boolean checkSearchQuota(long sessionId, String modelId, String today, String thisMonth) {
        if (!db.isToolEnabled(sessionId, Tools.SEARCH)) return false;

        if (modelId.startsWith("gemini-3")) {
            // Gemini 3 계열: 통합 월간 5k 쿼리
            int monthUsage = db.getGoogleSearchCount(thisMonth, "gemini-3%");
            if (monthUsage < 4950) return true;
            logger.warn("Gemini 3 통합 월간 검색 한도 초과");
        } else if (modelId.equals(GeminiModel.PRO25.getId())) {
            // Gemini 2.5 Pro: 일간 1.5k 쿼리
            int dailyUsage = db.getGoogleSearchCount(today, "gemini-2.5-pro");
            if (dailyUsage < 1450) return true;
            logger.warn("Gemini 2.5 Pro 일간 검색 한도 초과");
        } else {
            // Gemini 2 플래시 계열: 통합 일간 1.5k 쿼리
            int dailyUsage = db.getGoogleSearchCount(today, "gemini-2._-flash");
            if (dailyUsage < 1450) return true;
            logger.warn("Gemini 2 Flash 통합 일간 검색 한도 초과");
        }
        return false;
    }

    /**
     * Extracts hyperlinks from metadata and increments the quota counter.
     */
    private String extractSearchSources(List<Candidate> candidates, boolean allowGoogleSearch, boolean isQueryWise,
                                        String today, String modelId, String titleLabel) {
        if (!allowGoogleSearch || candidates.isEmpty()) return "";

        Candidate cand = candidates.getFirst();
        GroundingMetadata ground = cand.groundingMetadata().orElse(null);
        if (ground == null) return "";

        if (ground.searchEntryPoint().isPresent()) {
            int queriesUsed = isQueryWise ?
                    ground.webSearchQueries().orElse(new ArrayList<>()).size() : 1;
            db.addGoogleSearchCount(today, modelId, queriesUsed);
            logger.info("답변 생성 중 구글 검색 {}회 사용됨, 카운트 증가 완료 ({})", queriesUsed, modelId);
        }

        StringBuilder sourceText = new StringBuilder();
        if (ground.groundingChunks().isPresent()) {
            StringBuilder tempSourceText = new StringBuilder(titleLabel);
            int sourceCount = 0;
            for (GroundingChunk chunk : ground.groundingChunks().get()) {
                if (chunk.web().isPresent()) {
                    var web = chunk.web().get();
                    String title = web.title().orElse("참고 링크");
                    if (title.isBlank()) title = "참고 링크";
                    String uri = web.uri().orElse("");
                    if (!uri.isBlank()) {
                        tempSourceText.append("- [%s](<%s>)\n".formatted(title, uri));
                        sourceCount++;
                    }
                }
            }
            if (sourceCount > 0) sourceText.append(tempSourceText);
        }
        return sourceText.toString();
    }

    /**
     * Handles Function Call for tool IMAGE.
     */
    private void handleImageTool(long sessionId, Message message, List<Content> fullHistory, DBManager.SessionInfo info,
                                 FunctionCall funcCall, boolean allowGoogleSearch, boolean isQueryWise, String today,
                                 int[] tokens, String initialSourceText) {
        Map<String, Object> args = funcCall.args().orElse(new HashMap<>());
        String imagePrompt = (String) args.get("prompt");
        String preText = (String) args.getOrDefault("message", "");

        logger.info("이미지 생성 요청 감지. 프롬프트: {}", imagePrompt);
        logger.info("봇의 메시지: {}", preText);

        @SuppressWarnings("unchecked")
        List<String> refIds = (List<String>) args.getOrDefault("reference_ids", new ArrayList<>());
        List<Part> referenceParts = new ArrayList<>();

        for (String idStr : refIds) {
            boolean found = false;
            for (Content content : fullHistory) {
                List<Part> parts = content.parts().orElse(new ArrayList<>());
                for (int i = 0; i < parts.size(); i++) {
                    String text = parts.get(i).text().orElse("");
                    if (text.contains(FileUtil.imageFormat.formatted(idStr))) {
                        if (i + 1 < parts.size()) {
                            referenceParts.add(parts.get(i + 1));
                            logger.info("이미지 ID: {} 참조", idStr);
                            found = true;
                            break;
                        }
                    }
                }
                if (found) break;
            }
            if (!found) logger.warn("대화 내역에서 레퍼런스 이미지를 찾을 수 없음 (ID: {})", idStr);
        }

        GenerateContentResponse funcResponse = GeminiManager.generateImage(imagePrompt,
                referenceParts, allowGoogleSearch);

        List<Candidate> funcCandidates = funcResponse.candidates().orElse(new ArrayList<>());
        // 출처 리스트 추출
        String imageSourceText = extractSearchSources(funcCandidates, allowGoogleSearch, isQueryWise, today,
                "gemini-3.1-flash-image-preview", "이미지 참고 자료:\n");
        String finalSourceText = initialSourceText +
                (initialSourceText.isBlank() || imageSourceText.isBlank() ? "" : "\n") + imageSourceText;

        List<Part> funcParts = funcResponse.candidates().orElseThrow()
                .getFirst().content().orElseThrow().parts().orElseThrow();
        StringBuilder postText = new StringBuilder();
        byte[] imageBytes = null;

        for (Part part : funcParts) {
            if (part.text().isPresent()) {
                postText.append("\n").append(part.text().get()).append("\n");
            } else if (part.inlineData().isPresent()) {
                imageBytes = part.inlineData().get().data().orElseThrow();
            }
        }
        if (imageBytes == null) throw new RuntimeException("이미지 생성 결과가 null입니다.");

        int[] imageTokens = getTokenCount(funcResponse);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
        String fileName = LocalDateTime.now().format(dtf) + ".png";

        String funcText = preText + postText;
        String displayText = funcText +
                (funcText.isBlank() || finalSourceText.isBlank() ? "" : "\n\n") + finalSourceText;

        final byte[] finalImageBytes = imageBytes;
        final String mimeType = "image/png";
        FileUpload file = FileUpload.fromData(finalImageBytes, fileName);
        sendMessageInChunks(message, displayText, file, botMsg -> {
            db.addMessage(sessionId, botMsg.getIdLong(), "model", funcText, info.model(),
                    tokens[0], tokens[1], tokens[2], tokens[3], tokens[4], tokens[5],
                    imagePrompt, refIds, imageTokens[0], imageTokens[1], imageTokens[2],
                    imageTokens[3], imageTokens[4], imageTokens[5],
                    finalSourceText);

            FileUtil.upload(message.getJDA(), finalImageBytes, fileName, sessionId, mimeType)
                    .thenAccept(result -> {
                        if (result != null) {
                            db.addAttachment(sessionId,
                                    botMsg.getIdLong(),
                                    FileType.GENERATED.name(),
                                    result.url(),
                                    result.archiveMsgId(),
                                    mimeType,
                                    0);
                        }
                    });
        });
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

    private List<Content> parseFiles(JDA jda, List<Content> toParse) {
        List<Content> readyHistory = new ArrayList<>();

        for (Content c : toParse) {
            List<Part> readyParts = new ArrayList<>();

            for (Part p : c.parts().orElse(new ArrayList<>())) {
                String text = p.text().orElse(null);

                if (text != null && text.startsWith("[FILE|")) {
                    try {
                        // [FILE|ID|URI|URL|MIME]
                        String inner = text.substring(6, text.length() - 1);
                        String[] tokens = inner.split("[|]", 4);

                        long archiveId = Long.parseLong(tokens[0]);
                        String geminiUri = tokens[1].equals("null") ? null : tokens[1];
                        String url = tokens[2];
                        String mimeType = tokens[3];

                        // ID Tag for Reference Image Call
                        if (mimeType.startsWith("image/")) {
                            readyParts.add(Part.fromText(FileUtil.imageFormat.formatted(archiveId)));
                        }

                        readyParts.add(toFilePart(jda, url, archiveId, geminiUri, mimeType));
                    } catch (Exception e) {
                        logger.error("파일 태그 파싱 중 에러 발생: {}", text, e);
                        readyParts.add(Part.fromText("[파일 파싱 실패]"));
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

    private Part toFilePart(JDA jda, String oldUrl, long archiveMsgId, String geminiUri, String mimeType) {
        try {
            if (geminiUri != null && !geminiUri.isBlank()) {
                logger.info("파일 캐시 적중, 다운로드 스킵 (ID: {})", archiveMsgId);
                return Part.fromUri(geminiUri, mimeType);
            }

            logger.info("파일 캐시 미스, 다운로드/구글 업로드 진행 (ID: {})", archiveMsgId);
            FileUtil.FileInfo file = FileUtil.getSafeFileBytes(jda, db, oldUrl, archiveMsgId);
            if (file == null) throw new RuntimeException("가져온 파일 데이터가 null입니다.");

            String cleanUrl = oldUrl.split("\\?")[0];
            String fileName = cleanUrl.substring(cleanUrl.lastIndexOf("/") + 1);
            fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);

            String newUri = FileUtil.uploadToGemini(file.bytes(), mimeType, fileName);

            if (newUri != null) {
                db.updateGeminiUri(archiveMsgId, newUri);
                return Part.fromUri(newUri, mimeType);
            } else {
                logger.warn("구글 업로드 실패, 바이트 배열로 폴백 (ID: {})", archiveMsgId);
                return Part.fromBytes(file.bytes(), mimeType);
            }
        } catch (Exception e) {
            logger.error("디스코드 파일 다운로드 실패", e);
            return Part.fromText("[파일 로드 실패]");
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
