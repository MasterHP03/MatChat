package org.mat.tool;

import com.google.genai.Client;
import com.google.genai.types.*;
import org.mat.exception.NoResponseException;

import java.util.List;

/**
 * Direct messenger that communicates with Gemini API.
 */
public class GeminiManager {

    /**
     * Sends a requests to Gemini API and gets a response.
     * @param systemPrompt Bot's persona.
     * @param history Full context of the current session.
     * @param model Model ID to use.
     * @param userNote User-defined instruction.
     * @return Response from Gemini.
     * @throws RuntimeException If Gemini fails to generate a content
     * due to reasons like API error, quota exceeded or safety filter.
     */
    public static GenerateContentResponse generate(String systemPrompt, List<Content> history, String model, String userNote) throws RuntimeException {
        try (Client client = Client.builder()
                .apiKey(Config.getGeminiKey())
                .httpOptions(HttpOptions.builder()
                        .retryOptions(HttpRetryOptions.builder()
                                .attempts(Config.getMaxRetry())
                                .build())
                        .build())
                .build()) {
            // 시스템 프롬프트와 유저 노트 병합
            String finalPrompt = systemPrompt;
            if (!userNote.isBlank()) finalPrompt += "\n\n[User-defined Instruction]\n" + userNote;
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(
                            Part.fromText(finalPrompt)))
                    .build();

            // Gemini 호출
            GenerateContentResponse response = client.models.generateContent(
                    model, history, config
            );

            // 응답이 없을 경우, 필터 때문인지 검사
            if (response.candidates().isEmpty()) {
                String reason = "응답 없음";
                if (response.promptFeedback().isPresent()) {
                    GenerateContentResponsePromptFeedback feedback = response.promptFeedback().get();
                    reason = String.join(" | ",
                            feedback.blockReason().toString(),
                            feedback.blockReasonMessage().toString(),
                            feedback.safetyRatings().toString());
                }
                throw new NoResponseException(reason);
            }

            return response;
        }
    }

}
