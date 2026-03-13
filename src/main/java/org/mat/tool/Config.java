package org.mat.tool;

import io.github.cdimascio.dotenv.Dotenv;
import org.mat.def.GeminiModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manager of settings and external files like prompts.
 */
public class Config {
    // Reads Env.Var.s from .env file
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final String PERSONA_PATH = getPersonaPath();
    private static final Logger logger = LoggerFactory.getLogger(Config.class);

    public static String getDiscordToken() {
        return dotenv.get("DISCORD_TOKEN");
    }

    public static String getGeminiKey() {
        return dotenv.get("GEMINI_API_KEY");
    }

    public static String getDbUrl() {
        return dotenv.get("DB_URL");
    }

    public static String getPrefix() {
        return dotenv.get("PREFIX", "$");
    }

    public static String getDefaultPersona() {
        return dotenv.get("DEFAULT_PERSONA");
    }

    public static GeminiModel getDefaultModel() {
        String key = dotenv.get("DEFAULT_MODEL", "FLASH3");
        GeminiModel model = GeminiModel.from(key);

        return model != null ? model : GeminiModel.FLASH3;
    }

    public static String getPersonaPath() {
        return dotenv.get("PERSONA_PATH", "%s.txt");
    }

    public static int getMaxRetry() {
        try {
            String toParse = dotenv.get("MAX_RETRY", "1");
            return Integer.parseInt(toParse);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Gets cooldown for sending message to Bot.
     * @return Cooldown set.
     */
    public static long getCooldown() {
        try {
            String toParse = dotenv.get("COOLDOWN", "1000");
            return Long.parseLong(toParse);
        } catch (Exception e) {
            return 1000;
        }
    }

    public static int getChunkSize() {
        try {
            String toParse = dotenv.get("CHUNK_SIZE", "2000");
            return Integer.parseInt(toParse);
        } catch (Exception e) {
            return 2000;
        }
    }

    public static long getArchive() {
        try {
            String toParse = dotenv.get("ARCHIVE_CHANNEL", "-1");
            return Long.parseLong(toParse);
        } catch (Exception e) {
            return -1;
        }
    }

    public static int getMaxOutputToken() {
        try {
            String toParse = dotenv.get("MAX_OUTPUT_TOKEN", "4096");
            return Integer.parseInt(toParse);
        } catch (Exception e) {
            return 4096;
        }
    }

    public static String getSystemInstruction(String name) {
        try {
            Path path = getPathByName(name);
            if (Files.exists(path)) {
                return Files.readString(path);
            } else {
                logger.error("프롬프트 파일이 없습니다.");
                return "Generate response that is continued from Dialog and User Input";
            }
        } catch (IOException e) {
            logger.error("프롬프트 로드 중 에러 발생: ", e);
            return "";
        }
    }

    // Prevents File Traversal.
    public static boolean isValidPersona(String name) {
        if (name == null || !name.matches("^[a-zA-Z0-9_가-힣ㄱ-ㅎㅏ-ㅣ ]+$")) {
            return false;
        }
        return Files.exists(getPathByName(name));
    }

    private static Path getPathByName(String name) {
        return Paths.get(PERSONA_PATH.formatted(name));
    }
}
