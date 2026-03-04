package org.mat.def;

import com.google.genai.types.Schema;

import java.util.Map;

public enum Tools {
    IMAGE("generate_image",
            """
            Generate an image based on given prompt.
            Use this tool when User asks to generate a picture, painting or image.
            Don't explicitly send JSON FunctionCall data as a part of response.
            """,
            Schema.builder()
                    .type("OBJECT")
                    .properties(Map.of(
                            "prompt", Schema.builder()
                                    .type("STRING")
                                    .description("""
                                            Detailed, describing English prompt about the image to generate
                                            (e.g. A highly detailed digital painting of a cute orange cat)
                                            """)
                                    .build(),
                            "message", Schema.builder()
                                    .type("STRING")
                                    .description("""
                                            Text that contains AI's reaction to User's request.
                                            When you decided to use this tool,
                                            AND if you need to react to user or User wants you to react,
                                            you MUST write on this field, NOT generating a regular text response.
                                            """)
                                    .build()
                            ))
                    .required("prompt")
                    .build()
    );

    private final String toolName;
    private final String desc;
    private final Schema params;

    Tools(String toolName, String desc, Schema params) {
        this.toolName = toolName;
        this.desc = desc;
        this.params = params;
    }

    public String getToolName() {
        return toolName;
    }

    public String getDescription() {
        return desc;
    }

    public Schema getParameters() {
        return params;
    }

    /**
     * Converts user-input command into the enum.
     */
    public static Tools from(String text) {
        for (Tools s : values()) {
            if (s.name().equalsIgnoreCase(text) || s.toolName.equalsIgnoreCase(text)) {
                return s;
            }
        }
        return null;
    }
}
