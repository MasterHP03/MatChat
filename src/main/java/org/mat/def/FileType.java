package org.mat.def;

public enum FileType {

    INPUT, GENERATED;

    /**
     * Converts user-input command into the enum.
     */
    public static FileType from(String text) {
        for (FileType s : values()) {
            if (s.name().equalsIgnoreCase(text)) {
                return s;
            }
        }
        return null;
    }

}
