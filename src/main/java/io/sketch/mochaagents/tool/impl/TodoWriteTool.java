package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务清单管理工具 — 对齐 claude-code 的 TodoWriteTool.
 *
 * <p>管理 Agent 会话级任务清单，追踪 oldTodos→newTodos 状态变更。
 * 非只读（修改内部状态），低安全级别。
 * @author lanxia39@163.com
 */
public class TodoWriteTool extends AbstractTool {

    private static final String NAME = "todo_write";

    /** 会话级 todo 状态存储. */
    private final List<Map<String, Object>> todos = new ArrayList<>();

    public TodoWriteTool() {
        super(builder(NAME, "Manage a structured task list for tracking progress. "
                        + "Create, update, and mark tasks as completed.",
                SecurityLevel.LOW)
                .searchHint("manage the session task checklist")
        );
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("todos")
                .inputProperty("todos", "array",
                        "Array of todo items with id, status, and content", true)
                .outputType("object")
                .outputProperty("oldTodos", "array", "The todo list before update")
                .outputProperty("newTodos", "array", "The todo list after update")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("todos", new ToolInput("array", "Array of todo items", true));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    // ==================== Call ====================

    @Override
    @SuppressWarnings("unchecked")
    public Object call(Map<String, Object> arguments) {
        List<Map<String, Object>> newTodos = (List<Map<String, Object>>) arguments.get("todos");
        if (newTodos == null) newTodos = List.of();

        // Capture old state
        List<Map<String, Object>> oldTodos = new ArrayList<>();
        synchronized (todos) {
            for (Map<String, Object> t : todos) {
                oldTodos.add(new LinkedHashMap<>(t));
            }

            // Update to new state — ensure each item has an id
            todos.clear();
            for (Map<String, Object> item : newTodos) {
                Map<String, Object> copy = new LinkedHashMap<>(item);
                if (!copy.containsKey("id")) {
                    copy.put("id", UUID.randomUUID().toString().substring(0, 8));
                }
                todos.add(copy);
            }

            // If all done, clear
            boolean allDone = newTodos.stream()
                    .allMatch(t -> "completed".equals(t.getOrDefault("status", "")));
            if (allDone) {
                // Keep completed items for history
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oldTodos", oldTodos);
        result.put("newTodos", newTodos);
        return result;
    }

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> newTodos = (List<Map<String, Object>>) map.getOrDefault("newTodos", List.of());

        long pending = newTodos.stream().filter(t -> "pending".equals(t.get("status"))).count();
        long inProgress = newTodos.stream().filter(t -> "in_progress".equals(t.get("status"))).count();
        long completed = newTodos.stream().filter(t -> "completed".equals(t.get("status"))).count();

        StringBuilder sb = new StringBuilder();
        sb.append("Todos have been modified successfully. ");
        sb.append("Current status: ");
        sb.append(pending).append(" pending, ");
        sb.append(inProgress).append(" in progress, ");
        sb.append(completed).append(" completed. ");
        sb.append("Continue tracking progress with the todo list.");

        return sb.toString();
    }

    /** 获取当前 session 的 todo 列表快照. */
    public List<Map<String, Object>> getTodos() {
        synchronized (todos) {
            List<Map<String, Object>> snapshot = new ArrayList<>();
            for (Map<String, Object> t : todos) {
                snapshot.add(new LinkedHashMap<>(t));
            }
            return snapshot;
        }
    }
}
