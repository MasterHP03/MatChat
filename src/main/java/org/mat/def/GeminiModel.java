package org.mat.def;

public enum GeminiModel {
    PRO31("gemini-3.1-pro-preview"),
    // PRO3("gemini-3-pro-preview"),
    FLASH3("gemini-3-flash-preview"),
    FLASHLITE31("gemini-3.1-flash-lite-preview"),
    PRO25("gemini-2.5-pro"),
    FLASH25("gemini-2.5-flash"),
    FLASH20("gemini-2.0-flash");

    private final String id;

    GeminiModel(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /**
     * Converts user-input command into the enum.
     */
    public static GeminiModel from(String text) {
        for (GeminiModel s : values()) {
            if (s.name().equalsIgnoreCase(text) || s.id.equalsIgnoreCase(text)) {
                return s;
            }
        }
        return null;
    }
}
