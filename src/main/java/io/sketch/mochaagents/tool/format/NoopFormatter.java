// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.format;

/** No-op formatter — returns source as-is. Used for unsupported languages. */
public class NoopFormatter implements CodeFormatter {
    @Override public String format(String source) { return source; }
}
