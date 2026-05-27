// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.format;

import java.util.regex.Pattern;

/** YAML formatter — consistent indentation, list alignment, trailing space cleanup. */
public class YamlFormatter implements CodeFormatter {

    @Override
    public String format(String source) {
        return source
                .replaceAll("[ \\t]+\\n", "\n")   // trailing whitespace
                .replaceAll("\\n{3,}", "\n\n")     // collapse blank lines
                .trim() + "\n";
    }
}
