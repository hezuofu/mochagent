// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.internal;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolSchema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multi-language code formatter tool — SQL, Java, JSON, XML, Markdown, YAML.
 *
 * <p>Usage:
 * <pre>{@code
 * format_tool(language="sql", content="select * from users")
 * format_tool(file="src/Foo.java", content="public class Foo{}")
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class FormatTool extends AbstractTool {

    private static final String NAME = "format_code";

    public FormatTool() {
        super(builder(NAME, "Format source code. Supports SQL, Java, JSON, XML, Markdown, YAML.",
                SecurityLevel.LOW)
                .searchHint("format SQL Java JSON XML Markdown YAML code")
        );
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("content", "language")
                .inputProperty("content", "string", "Source code to format", true)
                .inputProperty("language", "string",
                        "Language: sql/java/json/xml/markdown/yaml, or auto-detect by file extension", false)
                .inputProperty("file", "string", "Optional file path for auto-detection", false)
                .outputType("object")
                .outputProperty("formatted", "string", "The formatted source code")
                .outputProperty("language", "string", "Detected language")
                .outputProperty("lines_in", "integer", "Original line count")
                .outputProperty("lines_out", "integer", "Formatted line count")
                .build();
    }

    @Override
    public Object call(Map<String, Object> arguments) {
        String content = (String) arguments.get("content");
        String language = (String) arguments.get("language");
        String file = (String) arguments.get("file");

        if (language == null || language.isBlank()) {
            language = file != null ? extension(file) : "plain";
        }

        String formatted = io.sketch.mochaagents.tool.format.CodeFormatter.format(language, content);

        // Count lines
        int inLines = content.split("\\r?\\n").length;
        int outLines = formatted.split("\\r?\\n").length;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formatted", formatted);
        result.put("language", language);
        result.put("lines_in", inLines);
        result.put("lines_out", outLines);
        return result;
    }

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map<?, ?> m)) {
            return "";
        }
        String result = (String) m.get("formatted");
        String lang = (String) m.get("language");
        int inLines = ((Number) m.get("lines_in")).intValue();
        int outLines = ((Number) m.get("lines_out")).intValue();

        return "```" + lang + "\n" + result + "\n```\n"
                + "_Formatted " + inLines + " → " + outLines + " lines (" + lang + ")_";
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot + 1).toLowerCase() : "plain";
    }
}
