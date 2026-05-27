// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.util.regex.Pattern;
import javax.xml.transform.*;
import javax.xml.transform.stream.*;

/**
 * Multi-language code formatter — enum strategy pattern, one constant per language.
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface CodeFormatter {

    String format(String source);

    // ── Format by language ──

    static String format(String language, String source) {
        return Lang.from(language).formatter.format(source);
    }

    static String formatByFile(String path, String source) {
        int dot = path.lastIndexOf('.');
        String ext = dot > 0 ? path.substring(dot + 1).toLowerCase() : "";
        return Lang.from(ext).formatter.format(source);
    }

    // ── Strategy enum ──

    enum Lang {
        SQL(new Sql()),
        JAVA(new Java()),
        JSON(new Json()),
        XML(new Xml()),
        HTML(new Xml()),  // same formatter
        SVG(new Xml()),
        MARKDOWN(new Markdown()),
        MD(new Markdown()),
        YAML(new Yaml()),
        YML(new Yaml()),
        PLAIN(new Plain());

        final CodeFormatter formatter;
        Lang(CodeFormatter f) { this.formatter = f; }

        static Lang from(String name) {
            try { return valueOf(name.toUpperCase()); }
            catch (IllegalArgumentException e) { return PLAIN; }
        }
    }

    // ── Strategy implementations ──

    /** SQL — keyword casing, clause alignment, subquery indentation. */
    final class Sql implements CodeFormatter {
        @Override public String format(String source) {
            return new SqlFormatter().format(source);
        }
    }

    /** Java — brace-based indentation, blank line cleanup. */
    final class Java implements CodeFormatter {
        private static final Pattern BLANKS = Pattern.compile("\\n{3,}");

        @Override public String format(String source) {
            String[] lines = source.split("\\r?\\n");
            StringBuilder sb = new StringBuilder();
            int indent = 0;
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty()) { sb.append('\n'); continue; }
                if (t.startsWith("}")) indent = Math.max(0, indent - 1);
                sb.append("    ".repeat(indent)).append(t).append('\n');
                if (t.endsWith("{") && !t.contains("{}")) indent++;
            }
            return BLANKS.matcher(sb.toString()).replaceAll("\n\n");
        }
    }

    /** JSON — Jackson pretty-print. */
    final class Json implements CodeFormatter {
        private static final ObjectMapper MAPPER = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);

        @Override public String format(String source) {
            try {
                Object tree = MAPPER.readTree(source);
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
            } catch (IOException e) { return source.trim() + "\n"; }
        }
    }

    /** XML/HTML — JDK Transformer indent. */
    final class Xml implements CodeFormatter {
        @Override public String format(String source) {
            try {
                Transformer t = TransformerFactory.newInstance().newTransformer();
                t.setOutputProperty(OutputKeys.INDENT, "yes");
                t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                var sw = new StringWriter();
                t.transform(new StreamSource(new StringReader(source)), new StreamResult(sw));
                return sw.toString();
            } catch (Exception e) { return source.trim() + "\n"; }
        }
    }

    /** Markdown — headings, blank lines, trailing spaces. */
    final class Markdown implements CodeFormatter {
        @Override public String format(String source) {
            return source
                    .replaceAll("^(#{1,6})(\\S)", "$1 $2")
                    .replaceAll("[ \\t]+\\n", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim() + "\n";
        }
    }

    /** YAML — trailing space cleanup. */
    final class Yaml implements CodeFormatter {
        @Override public String format(String source) {
            return source.replaceAll("[ \\t]+\\n", "\n")
                    .replaceAll("\\n{3,}", "\n\n").trim() + "\n";
        }
    }

    /** No-op passthrough. */
    final class Plain implements CodeFormatter {
        @Override public String format(String source) { return source; }
    }
}
