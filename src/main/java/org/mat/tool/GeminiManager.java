package org.mat.tool;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.*;
import org.mat.def.Tools;
import org.mat.event.MessageEvent;
import org.mat.exception.NoResponseException;
import org.mat.util.GeminiClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Direct messenger that communicates with Gemini API.
 */
public class GeminiManager {

    private static final Tool imageTool = getTool(Tools.IMAGE);
    private static final Tool searchTool = Tool.builder()
            .googleSearch(GoogleSearch.builder()
                    .build())
            .build();

    private static final Logger logger = LoggerFactory.getLogger(GeminiManager.class);

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
                                                   boolean enableImageTool, boolean enableSearchTool) throws RuntimeException {
        // 시스템 프롬프트와 유저 노트 병합
        String finalPrompt = systemPrompt;
        if (!userNote.isBlank()) finalPrompt += "\n\n[User-defined Instruction]\n" + userNote;

        var configBuilder = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(
                        Part.fromText(finalPrompt)))
                .maxOutputTokens(Config.getMaxOutputToken());
        List<Tool> tools = new ArrayList<>();
        if (enableImageTool) tools.add(imageTool);
        if (enableSearchTool) tools.add(searchTool);
        if (!tools.isEmpty()) configBuilder.tools(tools);

        var config = configBuilder.build();

        // Gemini 호출
        int attemptCount = 0;
        int totalClients = GeminiClientManager.getClientCount();

        while (attemptCount < totalClients) {
            try {
                Client client = GeminiClientManager.getClient();

                var response = client.models.generateContent(
                        model, history, config
                );

                // 응답이 없을 경우, 필터 때문인지 검사
                if (response.candidates().isEmpty()) {
                    String reason = "응답 없음";
                    if (response.promptFeedback().isPresent()) {
                        var feedback = response.promptFeedback().get();
                        reason = String.join(" | ",
                                feedback.blockReason().toString(),
                                feedback.blockReasonMessage().toString(),
                                feedback.safetyRatings().toString());
                    }
                    throw new NoResponseException(reason);
                }
                return response;
            } catch (ApiException e) {
                if (e.code() == 429) {
                    logger.info("현재 키 만료, 다음 키로 로테이션");
                    GeminiClientManager.rotateClient(); // 인덱스 + 1
                    attemptCount++;
                    if (attemptCount >= totalClients) {
                        throw e;
                    }
                } else throw e;
            }
        }
        return null;
    }

    public static GenerateContentResponse generateImage(String prompt, List<Part> referenceImages, boolean enableSearchTool) {
        try {
            List<Part> inputParts = new ArrayList<>();
            if (referenceImages != null && !referenceImages.isEmpty()) {
                inputParts.addAll(referenceImages);
            }
            inputParts.add(Part.fromText(prompt));

            Content inputContent = Content.builder().parts(inputParts).build();

            var configBuilder = GenerateContentConfig.builder()
                    .responseModalities("TEXT", "IMAGE");
            List<Tool> tools = new ArrayList<>();
            if (enableSearchTool) tools.add(searchTool);
            if (!tools.isEmpty()) configBuilder.tools(tools);
            var config = configBuilder.build();

            // 호출
            int attemptCount = 0;
            int totalClients = GeminiClientManager.getClientCount();

            while (attemptCount < totalClients) {
                try {
                    Client client = GeminiClientManager.getClient();

                    var response = client.models.generateContent(
                            "gemini-3.1-flash-image-preview", inputContent, config
                    );

                    // 응답이 없을 경우, 필터 때문인지 검사
                    if (response.candidates().isEmpty()) {
                        String reason = "응답 없음";
                        if (response.promptFeedback().isPresent()) {
                            var feedback = response.promptFeedback().get();
                            reason = String.join(" | ",
                                    feedback.blockReason().toString(),
                                    feedback.blockReasonMessage().toString(),
                                    feedback.safetyRatings().toString());
                        }
                        throw new NoResponseException(reason);
                    }
                    return response;
                } catch (ApiException e) {
                    if (e.code() == 429) {
                        logger.info("이미지 요청에서 현재 키 만료, 다음 키로 로테이션");
                        GeminiClientManager.rotateClient(); // 인덱스 + 1
                        attemptCount++;
                        if (attemptCount >= totalClients) {
                            throw e;
                        }
                    } else throw e;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("이미지 생성 실패: " + e.getMessage(), e);
        }

        return null;
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
