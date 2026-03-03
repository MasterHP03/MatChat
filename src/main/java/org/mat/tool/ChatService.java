package org.mat.tool;

import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import net.dv8tion.jda.api.entities.Message;
import org.mat.exception.NoResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
        // 1. 이번 턴 유저 메시지 객체 생성
        String userMsg = message.getContentRaw();
        Content query = Content.builder()
                .role("user")
                .parts(Part.fromText(userMsg))
                .build();
        // 2. 히스토리 병합 (ImmutableList 예방 Copy)
        List<Content> fullHistory = new ArrayList<>(db.getStructuredHistory(sessionId));
        fullHistory.add(query);

        generateAndReply(sessionId, fullHistory, message, userMsg);
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

        generateAndReply(sessionId, fullHistory, message, null);
    }

    /**
     * Receives a response from Gemini and sends it to Discord.
     * If the response is too long, it slices it before sending.
     * @param sessionId ID of chat session.
     * @param fullHistory Full context of the session.
     * @param message User's current message (for reply).
     * @param newUserInput User's new input message. If null (when reroll), the data is not added to DB.
     */
    public void generateAndReply(long sessionId, List<Content> fullHistory, Message message, String newUserInput) {
        DBManager.SessionInfo info = db.getSessionInfo(sessionId);
        String systemPrompt = Config.getSystemInstruction(info.persona());

        try {
            message.getChannel().sendTyping().queue();

            // Gemini 호출
            GenerateContentResponse response = GeminiManager.generate(systemPrompt, fullHistory, info.model(), info.userNote());
            String responseText = response.text() == null ? "응답 없음" : response.text();

            // 토큰 사용량 계산
            int[] tokens = getTokenCount(response);

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
                        // 메시지 전송 성공 시에만 DB에 현재 턴 모두 저장
                        // 청크 나눠보낼 때, 첫 청크를 보낼 때만 저장되도록
                        if (newUserInput != null) {
                            db.addMessage(sessionId,message.getIdLong(), "user", newUserInput);
                        }
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
