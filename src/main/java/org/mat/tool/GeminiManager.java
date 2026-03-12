package org.mat.tool;

import com.google.genai.Client;
import com.google.genai.types.*;
import org.mat.def.Tools;
import org.mat.exception.NoResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Direct messenger that communicates with Gemini API.
 */
public class GeminiManager {

    private static final Tool imageTool = getTool(Tools.IMAGE);

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
    public static GenerateContentResponse generate(String systemPrompt, List<Content> history,
                                                   String model, String userNote,
                                                   boolean enableImageTool) throws RuntimeException {
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

            GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(
                            Part.fromText(finalPrompt)))
                    .maxOutputTokens(Config.getMaxOutputToken());
            List<Tool> tools = new ArrayList<>();
            if (enableImageTool) {
                tools.add(imageTool);
            }
            if (!tools.isEmpty()) configBuilder.tools(tools);

            GenerateContentConfig config = configBuilder.build();

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

    public static GenerateContentResponse generateImage(String prompt, List<Part> referenceImages) {
        try (Client client = Client.builder()
                .apiKey(Config.getGeminiKey())
                .httpOptions(HttpOptions.builder()
                        .retryOptions(HttpRetryOptions.builder()
                                .attempts(Config.getMaxRetry())
                                .build())
                        .build())
                .build()) {
            List<Part> inputParts = new ArrayList<>();
            if (referenceImages != null && !referenceImages.isEmpty()) {
                inputParts.addAll(referenceImages);
            }
            inputParts.add(Part.fromText(prompt));

            Content inputContent = Content.builder().parts(inputParts).build();

            GenerateContentResponse response = client.models.generateContent(
                    "gemini-3.1-flash-image-preview",
                    inputContent,
                    GenerateContentConfig.builder()
                            .responseModalities("TEXT", "IMAGE")
                            .build()
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
        } catch (Exception e) {
            throw new RuntimeException("이미지 생성 실패: " + e.getMessage(), e);
        }
    }

    private static Tool getTool(Tools tool) {
        return Tool.builder()
                .functionDeclarations(FunctionDeclaration.builder()
                        .name(tool.getToolName())
                        .description(tool.getDescription())
                        .parameters(tool.getParameters())
                ).build();
    }

}
