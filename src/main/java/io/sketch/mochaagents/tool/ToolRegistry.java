package io.sketch.mochaagents.tool;

import io.sketch.mochaagents.skill.SkillRegistry;
import io.sketch.mochaagents.tool.impl.SkillTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 工具注册表 — 对齐 claude-code 的 Tools 集合管理.
 *
 * <p>新增:
 * <ul>
 *   <li>按名查找（含别称）</li>
 *   <li>条件过滤</li>
 *   <li>按搜索提示匹配</li>
 *   <li>安全分类（只读/可写）</li>
 * </ul>
 * @author lanxia39@163.com
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, String> aliasToName = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        for (String alias : tool.getAliases()) {
            aliasToName.put(alias, tool.getName());
        }
        log.debug("Tool registered: {} (aliases: {})", tool.getName(), tool.getAliases());
    }

    public void unregister(String name) {
        Tool tool = tools.remove(name);
        if (tool != null) {
            tool.getAliases().forEach(aliasToName::remove);
            log.debug("Tool unregistered: {}", name);
        }
    }

    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool != null) return tool;
        String resolved = aliasToName.get(name);
        return resolved != null ? tools.get(resolved) : null;
    }

    public boolean has(String name) {
        return tools.containsKey(name) || aliasToName.containsKey(name);
    }

    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public int size() { return tools.size(); }

    // ---- 查找与过滤 ----

    /** 按名查找（含别称匹配）. */
    public Tool findByName(String name) {
        return get(name);
    }

    /** 条件过滤，返回新 List. */
    public List<Tool> filter(Predicate<Tool> predicate) {
        return tools.values().stream().filter(predicate).collect(Collectors.toList());
    }

    /** 按搜索提示关键词匹配. */
    public List<Tool> searchByHint(String keyword) {
        String lower = keyword.toLowerCase();
        return tools.values().stream()
                .filter(t -> t.getSearchHint().toLowerCase().contains(lower)
                        || t.getName().toLowerCase().contains(lower))
                .collect(Collectors.toList());
    }

    // ---- 安全分类 ----

    /** 只读工具列表. */
    public List<Tool> getReadOnlyTools() {
        return filter(Tool::isReadOnly);
    }

    /** 可写/可能修改状态的工具列表. */
    public List<Tool> getWritableTools() {
        return filter(t -> !t.isReadOnly());
    }

    /** 并发安全工具列表. */
    public List<Tool> getConcurrencySafeTools() {
        return filter(Tool::isConcurrencySafe);
    }

    /** 已启用工具列表. */
    public List<Tool> getEnabledTools() {
        return filter(Tool::isEnabled);
    }

    // ---- Skill 桥接 ----

    /**
     * 注册 SkillTool 桥接器.
     * 将 SkillRegistry 中的技能通过 SkillTool 暴露给 LLM 调用。
     */
    public SkillTool registerSkillTool(SkillRegistry skillRegistry) {
        SkillTool skillTool = new SkillTool(skillRegistry);
        register(skillTool);
        log.info("SkillTool registered with {} skills", skillRegistry.size());
        return skillTool;
    }

    /** 查找 SkillTool（如果已注册）. */
    public SkillTool findSkillTool() {
        Tool tool = get(SkillTool.TOOL_NAME);
        return tool instanceof SkillTool ? (SkillTool) tool : null;
    }
}
