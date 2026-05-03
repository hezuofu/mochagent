package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.skill.ContentBlock;
import io.sketch.mochaagents.skill.Skill;
import io.sketch.mochaagents.skill.SkillRegistry;
import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.PermissionResult;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能调用工具 — 对齐 claude-code 的 SkillTool.
 *
 * <p>模型通过此工具调用已注册技能。技能本身不执行副作用，
 * 而是返回 prompt 内容注入对话上下文，引导模型按专家知识流程工作。
 *
 * <p>调用流程:
 * <ol>
 *   <li>validateInput: 检查技能名是否存在、未被禁用</li>
 *   <li>checkPermissions: 内置技能自动允许，其他需确认</li>
 *   <li>call: 调用 skill.getPromptForCommand(args)，将 prompt 作为 newMessages 注入</li>
 * </ol>
 * @author lanxia39@163.com
 */
public class SkillTool extends AbstractTool {

    public static final String TOOL_NAME = "Skill";

    private final SkillRegistry skillRegistry;

    public SkillTool(SkillRegistry skillRegistry) {
        super(builder(TOOL_NAME,
                "Execute a skill within the main conversation. "
                        + "Skills are specialized prompt templates that guide the model through specific workflows.",
                SecurityLevel.LOW)
                .searchHint("invoke a skill command")
        );
        this.skillRegistry = skillRegistry;
    }

    // ==================== Schema ====================

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("skill")
                .inputProperty("skill", "string",
                        "The skill name. E.g., \"commit\", \"review-pr\", or \"pdf\"", true)
                .inputProperty("args", "string",
                        "Optional arguments for the skill", false)
                .outputType("object")
                .outputProperty("success", "boolean", "Whether the skill was executed")
                .outputProperty("commandName", "string", "The name of the skill")
                .outputProperty("status", "string", "Execution status: 'inline'")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("skill", new ToolInput("string", "The skill name", false));
        inputs.put("args", new ToolInput("string", "Optional arguments for the skill", true));
        return inputs;
    }

    @Override
    public String getOutputType() {
        return "object";
    }

    // ==================== 校验 ====================

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String skillName = (String) arguments.get("skill");
        if (skillName == null || skillName.trim().isEmpty()) {
            return ValidationResult.invalid("Skill name is required", 1);
        }

        String trimmed = skillName.trim();

        // Strip leading slash for compatibility
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }

        // Check skill exists
        Skill skill = skillRegistry.findByName(trimmed);
        if (skill == null) {
            return ValidationResult.invalid("Unknown skill: " + trimmed, 2);
        }

        // Check skill is enabled
        if (!skill.isEnabled()) {
            return ValidationResult.invalid("Skill is disabled: " + trimmed, 3);
        }

        // Check disableModelInvocation
        if (skill.disableModelInvocation()) {
            return ValidationResult.invalid(
                    "Skill " + trimmed + " cannot be invoked by model (disableModelInvocation)", 4);
        }

        return ValidationResult.valid();
    }

    // ==================== 权限 ====================

    @Override
    public PermissionResult checkPermissions(Map<String, Object> arguments) {
        String skillName = (String) arguments.get("skill");
        String trimmed = skillName != null && skillName.startsWith("/")
                ? skillName.substring(1)
                : skillName != null ? skillName : "";

        Skill skill = skillRegistry.findByName(trimmed);

        // Auto-allow bundled skills (they ship with the framework)
        if (skill != null && skill.source() == io.sketch.mochaagents.skill.SkillSource.BUNDLED) {
            return PermissionResult.allow(arguments, "Bundled skill — auto-allowed");
        }

        // For file-system skills, ask the user
        return PermissionResult.ask("Execute skill: " + trimmed,
                "File-system skills require user confirmation");
    }

    // ==================== 执行 ====================

    @Override
    public Object call(Map<String, Object> arguments) {
        String rawSkillName = (String) arguments.get("skill");
        String args = (String) arguments.getOrDefault("args", "");

        String skillName = rawSkillName != null && rawSkillName.startsWith("/")
                ? rawSkillName.substring(1)
                : rawSkillName != null ? rawSkillName : "";

        Skill skill = skillRegistry.findByName(skillName);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }

        // Generate skill prompt
        List<ContentBlock> blocks = skill.getPromptForCommand(args != null ? args : "");
        String promptText = blocks.stream()
                .map(ContentBlock::text)
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");

        // Build result with skill metadata
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("commandName", skillName);
        result.put("status", "inline");

        // Attach allowed tools if specified
        List<String> allowedTools = skill.allowedTools();
        if (!allowedTools.isEmpty()) {
            result.put("allowedTools", allowedTools);
        }

        // Attach model override if specified
        String model = skill.model();
        if (model != null && !model.isEmpty()) {
            result.put("model", model);
        }

        return result;
    }

    // ==================== 格式化 ====================

    @Override
    @SuppressWarnings("unchecked")
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) {
            return output != null ? output.toString() : "";
        }
        Map<String, Object> result = (Map<String, Object>) output;
        String commandName = (String) result.getOrDefault("commandName", "unknown");
        return "Launching skill: " + commandName;
    }

    /**
     * 将 skill 的 prompt 内容转换为 newMessages 格式.
     * 供 ToolExecutor/Orchestrator 在 call 后提取并注入 LLM 上下文。
     *
     * @param arguments 原始调用参数
     * @return newMessages 列表，每项包含 type 和 content
     */
    public List<Map<String, Object>> getSkillNewMessages(Map<String, Object> arguments) {
        String rawSkillName = (String) arguments.get("skill");
        String args = (String) arguments.getOrDefault("args", "");

        String skillName = rawSkillName != null && rawSkillName.startsWith("/")
                ? rawSkillName.substring(1)
                : rawSkillName != null ? rawSkillName : "";

        Skill skill = skillRegistry.findByName(skillName);
        if (skill == null) {
            return List.of();
        }

        List<ContentBlock> blocks = skill.getPromptForCommand(args != null ? args : "");
        String promptText = blocks.stream()
                .map(ContentBlock::text)
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "user");
        msg.put("content", promptText);
        msg.put("isMeta", true);
        msg.put("skillName", skillName);

        return List.of(msg);
    }
}
