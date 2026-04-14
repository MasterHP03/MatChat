package org.mat.util;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.mat.tool.Config;

public class GeminiClientManager {
    private static final Client INSTANCE = Client.builder()
            .apiKey(Config.getGeminiKey())
            .httpOptions(HttpOptions.builder()
                    .retryOptions(HttpRetryOptions.builder()
                            .attempts(Config.getMaxRetry())
                            .build())
                    .timeout(5 * 60 * 1000)
                    .build())
            .build();

    public static Client getClient() {
        return INSTANCE;
    }
}
