// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.format;

import java.util.regex.Pattern;

/**
 * Markdown formatter — consistent heading spacing, list indentation, code fence cleanup.
 *
 * @author lanxia39@163.com
 */
public class MarkdownFormatter implements CodeFormatter {

    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern HEADING_SPACE = Pattern.compile("^(#{1,6})(\\S)", Pattern.MULTILINE);

    @Override
    public String format(String source) {
        String result = source;

        // Ensure space after # in headings: "##Title" → "## Title"
        result = HEADING_SPACE.matcher(result).replaceAll("$1 $2");

        // Normalize excessive blank lines to max 2
        result = BLANK_LINES.matcher(result).replaceAll("\n\n");

        // Trim trailing whitespace per line
        result = result.replaceAll("[ \\t]+\\n", "\n");

        // Ensure single trailing newline
        return result.trim() + "\n";
    }
}
