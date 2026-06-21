// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * 工具 Schema 描述 — 对齐 claude-code 的 Zod schema / JSON Schema.
 *
 * 描述工具的输入参数结构和输出结构，供 Model 理解工具契约。
 * 支持 Map→Record 验证与自动转换。
 * @author lanxia39@163.com
 */
public final class ToolSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;

    private ToolSchema(Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        this.inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        this.outputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(outputSchema));
    }

    public Map<String, Object> getInputSchema() { return inputSchema; }
    public Map<String, Object> getOutputSchema() { return outputSchema; }

    // ── Schema-aware validation: Map → typed Record ──

    /**
     * Validate arguments against this schema and convert to a typed record.
     * Pydantic/Zod pattern: Map arguments → validate → typed object.
     *
     * @param arguments raw tool arguments from Model
     * @param targetType target record class (e.g. {@code ReadFileInput.class})
     * @param <T> the record type
     * @return validation result with typed value or error message
     */
    public <T> ValidationResult validate(Map<String, Object> arguments, Class<T> targetType) {
        // 1. Check required fields (List or String[])
        Object requiredObj = inputSchema.get("required");
        if (requiredObj instanceof List<?> required) {
            for (Object f : required) {
                String field = f.toString();
                if (!arguments.containsKey(field)) {
                    return ValidationResult.invalid("Missing required parameter: " + field);
                }
            }
        } else if (requiredObj instanceof String[] required) {
            for (String field : required) {
                if (!arguments.containsKey(field)) {
                    return ValidationResult.invalid("Missing required parameter: " + field);
                }
            }
        }

        // 2. Type checking against schema properties
        Object propsObj = inputSchema.get("properties");
        if (propsObj instanceof Map<?, ?> props) {
            for (var entry : props.entrySet()) {
                String fieldName = entry.getKey().toString();
                Object value = arguments.get(fieldName);
                if (value == null) {
                    continue;
                }

                if (entry.getValue() instanceof Map<?, ?> fieldSchema) {
                    String expectedType = Objects.toString(fieldSchema.get("type"), "");
                    if (!typeMatches(value, expectedType)) {
                        return ValidationResult.invalid(
                                "Parameter '" + fieldName + "' expected " + expectedType
                                + " but got " + value.getClass().getSimpleName());
                    }
                }
            }
        }

        // 3. Convert Map → Record using Jackson (Pydantic BaseModel equivalent)
        try {
            T typed = MAPPER.convertValue(arguments, targetType);
            return ValidationResult.validWith(typed);
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid("Type conversion failed: " + e.getMessage());
        }
    }

    private static boolean typeMatches(Object value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "integer", "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true; // unknown type, allow through
        };
    }

    // ── Static factory ──

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
