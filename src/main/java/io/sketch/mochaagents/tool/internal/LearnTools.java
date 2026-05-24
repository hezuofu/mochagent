// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.internal;

import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ValidationResult;
import java.util.*;

public final class LearnTools {
    private LearnTools() {}

    public static final class UpdateCheckpoint extends AbstractTool {
        private final MemoryManager mem;
        public UpdateCheckpoint(MemoryManager m) { super(builder("update_checkpoint", "Set key_info and related_sop for this task.", SecurityLevel.LOW)); this.mem = m; }
        @Override public Map<String, ToolInput> getInputs() { Map<String, ToolInput> in = new LinkedHashMap<>(); in.put("key_info", ToolInput.string("Critical info")); in.put("related_sop", new ToolInput("string", "SOP file reference", true)); return in; }
        @Override public String getOutputType() { return "object"; }
        @Override public Object call(Map<String, Object> a) { mem.checkpoint((String)a.get("key_info"), (String)a.get("related_sop")); return Map.of("status", "updated"); }
    }

    public static final class SettleLongTerm extends AbstractTool {
        private final MemoryManager mem;
        public SettleLongTerm(MemoryManager m) { super(builder("start_long_term_update", "Persist VERIFIED fact to global memory.", SecurityLevel.LOW)); this.mem = m; }
        @Override public Map<String, ToolInput> getInputs() { return Map.of("entry", ToolInput.string("Verified declarative fact")); }
        @Override public String getOutputType() { return "object"; }
        @Override public ValidationResult validateInput(Map<String, Object> a) { String e = (String) a.get("entry"); if (e == null || e.isBlank()) return ValidationResult.invalid("entry required", 1); return ValidationResult.valid(); }
        @Override public Object call(Map<String, Object> a) { mem.settle((String) a.get("entry")); return Map.of("status", "persisted"); }
    }
}
