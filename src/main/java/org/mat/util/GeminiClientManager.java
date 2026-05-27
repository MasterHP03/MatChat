package org.mat.util;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.mat.tool.Config;

import java.util.ArrayList;
import java.util.List;

public class GeminiClientManager {
    private static final List<Client> INSTANCES = new ArrayList<>();
    private static volatile int currentIndex = 0;
    private static volatile String hashedKey;

    static {
        String[] keys = Config.getGeminiKeys();
        for (String key : keys) {
            Client client = Client.builder()
                    .apiKey(key.trim())
                    .httpOptions(HttpOptions.builder()
                            .retryOptions(HttpRetryOptions.builder()
                                    .attempts(Config.getMaxRetry())
                                    .build())
                            .timeout(5 * 60 * 1000)
                            .build())
                    .build();
            INSTANCES.add(client);
        }
        hashedKey = MatHash.hashApiKey(keys[0].trim());
    }

    public static synchronized Client getClient() {
        if (INSTANCES.isEmpty()) {
            throw new IllegalStateException("No API Key registered");
        }
        return INSTANCES.get(currentIndex);
    }

    public static synchronized void rotateClient() {
        currentIndex = (currentIndex + 1) % INSTANCES.size();
        hashedKey = MatHash.hashApiKey(INSTANCES.get(currentIndex).apiKey()).trim();
    }

    public static int getClientCount() {
        return INSTANCES.size();
    }

    public static synchronized String getHashedKey() {
        return hashedKey;
    }
}
