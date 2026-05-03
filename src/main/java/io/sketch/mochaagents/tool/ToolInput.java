package io.sketch.mochaagents.tool;

/**
 * 工具输入参数描述.
 * @author lanxia39@163.com
 */
public final class ToolInput {
    private final String type;
    private final String description;
    private final boolean nullable;

    public ToolInput(String type, String description, boolean nullable) { this.type = type; this.description = description; this.nullable = nullable; }
    public String type() { return type; }
    public String description() { return description; }
    public boolean nullable() { return nullable; }

    public static ToolInput string(String description) { return new ToolInput("string", description, false); }
    public static ToolInput integer(String description) { return new ToolInput("integer", description, false); }
    public static ToolInput booleanInput(String description) { return new ToolInput("boolean", description, false); }
    public static ToolInput any(String description) { return new ToolInput("any", description, true); }
}
