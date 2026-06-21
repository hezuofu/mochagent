// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

/**
 * Session-level working memory — key info and SOP references for the current task.
 *
 * <p>Extracted from MemoryManager following the hermes-agent pattern where
 * working memory is a distinct concern from step tracking and persistence.
 * Injected into the system prompt via {@link MemoryManager#workingContext()}.
 *
 * <p>Pattern from GenericAgent's do_update_working_checkpoint().
 *
 * @author lanxia39@163.com
 */
public class WorkingContext {

    private String keyInfo = "";
    private String relatedSop = "";

    /** Update the working context. Null or blank values are ignored. */
    public void checkpoint(String keyInfo, String relatedSop) {
        if (keyInfo != null && !keyInfo.isBlank()) {
            this.keyInfo = keyInfo;
        }
        if (relatedSop != null && !relatedSop.isBlank()) {
            this.relatedSop = relatedSop;
        }
    }

    public String keyInfo() {
        return keyInfo;
    }

    public String relatedSop() {
        return relatedSop;
    }

    /** Clear all working context. */
    public void reset() {
        keyInfo = "";
        relatedSop = "";
    }

    /** Build the prompt fragment for the system prompt. */
    public String workingContext() {
        if (keyInfo.isEmpty() && relatedSop.isEmpty()) {
            return "";
        }
        return "\n## Working Memory\n"
                + (keyInfo.isEmpty() ? "" : "<key_info>" + keyInfo + "</key_info>\n")
                + (relatedSop.isEmpty() ? "" : "Check " + relatedSop + " if unclear.\n");
    }
}
