// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.format;

/**
 * Multi-language code formatter — clean, consistent output for any language.
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface CodeFormatter {

    /** Format the given source code. */
    String format(String source);

    /** Detect the appropriate formatter by file extension. */
    static CodeFormatter forFile(String path) {
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "sql"  -> new SqlFormatter();
            case "java" -> new JavaFormatter();
            case "json" -> new JsonFormatter();
            case "xml", "html", "svg" -> new XmlFormatter();
            case "md", "markdown" -> new MarkdownFormatter();
            case "yml", "yaml" -> new YamlFormatter();
            default -> new NoopFormatter();
        };
    }

    /** Detect by language name. */
    static CodeFormatter forLanguage(String lang) {
        return switch (lang.toLowerCase()) {
            case "sql"      -> new SqlFormatter();
            case "java"     -> new JavaFormatter();
            case "json"     -> new JsonFormatter();
            case "xml", "html" -> new XmlFormatter();
            case "markdown", "md" -> new MarkdownFormatter();
            case "yaml", "yml" -> new YamlFormatter();
            default -> new NoopFormatter();
        };
    }
}
