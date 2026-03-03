package org.mat.def;

public enum SessionSetting {
    PERSONA("persona"),
    MODEL("model"),
    NOTE("user_note");

    private final String columnName;

    SessionSetting(String columnName) {
        this.columnName = columnName;
    }

    public String getColumnName() {
        return columnName;
    }

    /**
     * Converts user-input command into the enum.
     */
    public static SessionSetting from(String text) {
        for (SessionSetting s : values()) {
            if (s.name().equalsIgnoreCase(text) || s.columnName.equalsIgnoreCase(text)) {
                return s;
            }
        }
        return null;
    }
}
