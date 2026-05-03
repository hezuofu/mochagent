package io.sketch.mochaagents.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具 Schema 描述 — 对齐 claude-code 的 Zod schema / JSON Schema.
 *
 * 描述工具的输入参数结构和输出结构，供 LLM 理解工具契约。
 * @author lanxia39@163.com
 */
public final class ToolSchema {

    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;

    private ToolSchema(Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        this.inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        this.outputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(outputSchema));
    }

    public Map<String, Object> getInputSchema() { return inputSchema; }
    public Map<String, Object> getOutputSchema() { return outputSchema; }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static ToolSchema of(Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        return new ToolSchema(inputSchema, outputSchema);
    }

    /** 仅输入 schema（输出默认为 string）. */
    public static ToolSchema inputOnly(Map<String, Object> inputSchema) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", Collections.singletonMap("result",
                Collections.singletonMap("type", "string")));
        return new ToolSchema(inputSchema, out);
    }

    public static final class Builder {
        private final Map<String, Object> input = new LinkedHashMap<>();
        private final Map<String, Object> output = new LinkedHashMap<>();
        private Map<String, Object> currentInputProps = new LinkedHashMap<>();

        public Builder inputType(String type) {
            input.put("type", type);
            return this;
        }

        public Builder inputRequired(String... required) {
            input.put("required", required);
            return this;
        }

        public Builder inputProperty(String name, String type, String description, boolean required) {
            LinkedHashMap<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", type);
            prop.put("description", description);
            currentInputProps.put(name, prop);
            input.put("properties", new LinkedHashMap<>(currentInputProps));
            return this;
        }

        public Builder outputType(String type) {
            output.put("type", type);
            return this;
        }

        public Builder outputDescription(String description) {
            output.put("description", description);
            return this;
        }

        public Builder outputProperty(String name, String type, String description) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) output.computeIfAbsent("properties",
                    k -> new LinkedHashMap<>());
            LinkedHashMap<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", type);
            prop.put("description", description);
            props.put(name, prop);
            return this;
        }

        public ToolSchema build() {
            return new ToolSchema(new LinkedHashMap<>(input), new LinkedHashMap<>(output));
        }
    }
}
