package org.mat.def;

public enum ImageType {

    INPUT, GENERATED;

    /**
     * Converts user-input command into the enum.
     */
    public static ImageType from(String text) {
        for (ImageType s : values()) {
            if (s.name().equalsIgnoreCase(text)) {
                return s;
            }
        }
        return null;
    }

}
