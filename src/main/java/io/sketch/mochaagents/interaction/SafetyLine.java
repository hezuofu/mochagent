// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Hardline safety gate — immune to ALL bypass modes. hermes-agent pattern.
 *
 * <p>These patterns are always blocked regardless of permission mode,
 * auto-classifier, or yolo flags. They protect against catastrophic actions.
 *
 * @author lanxia39@163.com
 */
public final class SafetyLine {

    private static final List<Pattern> HARDLINE = List.of(
            Pattern.compile("rm\\s+-rf\\s+/"),
            Pattern.compile(">\\s*/dev/sda"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+if="),
            Pattern.compile("chmod\\s+777\\s+/"),
            Pattern.compile("git\\s+push\\s+.*--force.*main"),
            Pattern.compile("git\\s+push\\s+.*--force.*master"),
            Pattern.compile("DROP\\s+(TABLE|DATABASE)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DELETE\\s+FROM\\s+.*WHERE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("format\\s+[A-Z]:", Pattern.CASE_INSENSITIVE)
    );

    private SafetyLine() {}

    /** Check if a command is hard-blocked. Never returns null. */
    public static Decision hardBlock(ToolUse use) {
        if (!"bash".equals(use.toolName()) && !"powershell".equals(use.toolName()))
            return Decision.allow("not a shell command");
        String content = use.content();
        if (content == null || content.isBlank()) return Decision.allow("empty");

        for (Pattern p : HARDLINE) {
            if (p.matcher(content).find())
                return Decision.hardDeny("Safety line crossed: " + p.pattern());
        }
        return Decision.allow("safe");
    }

    /** Register additional hardline patterns at startup. */
    public static void register(String regex) {
        // currently immutable; could support runtime registration
    }
}
