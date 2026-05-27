// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.format;

import java.util.regex.*;

/**
 * Basic Java formatter — indentation, brace placement, blank line cleanup.
 *
 * @author lanxia39@163.com
 */
public class JavaFormatter implements CodeFormatter {

    private static final Pattern INDENT_OPEN = Pattern.compile("\\{\\s*$");
    private static final Pattern INDENT_CLOSE = Pattern.compile("^\\s*\\}");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n\\s*\\n+");

    @Override
    public String format(String source) {
        String[] lines = source.split("\\r?\\n");
        StringBuilder result = new StringBuilder();
        int indent = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                result.append("\n");
                continue;
            }

            // Decrease indent BEFORE closing brace
            if (INDENT_CLOSE.matcher(trimmed).matches()) {
                indent = Math.max(0, indent - 1);
            }

            result.append("    ".repeat(Math.max(0, indent)));
            result.append(trimmed).append("\n");

            // Increase indent AFTER opening brace
            if (INDENT_OPEN.matcher(trimmed).find()) {
                indent++;
            }
        }

        // Clean up excessive blank lines
        return BLANK_LINES.matcher(result.toString()).replaceAll("\n\n").trim() + "\n";
    }
}
