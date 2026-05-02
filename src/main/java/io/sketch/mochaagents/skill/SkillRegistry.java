package io.sketch.mochaagents.skill;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 技能注册表 — 对齐 claude-code 的 bundledSkills[] + getCommands().
 *
 * <p>线程安全，支持:
 * <ul>
 *   <li>按名查找（含别名）</li>
 *   <li>条件过滤</li>
 *   <li>按来源 / 启用状态分类</li>
 *   <li>批量注册（如从文件系统加载后合并）</li>
 * </ul>
 */
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Map<String, String> aliasToName = new ConcurrentHashMap<>();

    // ==================== 注册/注销 ====================

    /**
     * 注册技能.
     * 同名技能后注册覆盖先注册（符合 claude-code 的 deeper-path-first 语义）.
     */
    public void register(Skill skill) {
        skills.put(skill.name(), skill);
        for (String alias : skill.aliases()) {
            aliasToName.put(alias, skill.name());
        }
    }

    /** 批量注册. */
    public void registerAll(Collection<? extends Skill> skillList) {
        for (Skill s : skillList) {
            register(s);
        }
    }

    /** 注销技能及其别名. */
    public void unregister(String name) {
        Skill skill = skills.remove(name);
        if (skill != null) {
            skill.aliases().forEach(aliasToName::remove);
        }
    }

    /** 清空注册表. */
    public void clear() {
        skills.clear();
        aliasToName.clear();
    }

    // ==================== 查找 ====================

    /** 按名查找（含别名匹配）. */
    public Skill findByName(String name) {
        Skill skill = skills.get(name);
        if (skill != null) return skill;
        String resolved = aliasToName.get(name);
        return resolved != null ? skills.get(resolved) : null;
    }

    /** 检查技能是否存在. */
    public boolean has(String name) {
        return skills.containsKey(name) || aliasToName.containsKey(name);
    }

    /** 获取所有技能（不可变视图）. */
    public Collection<Skill> all() {
        return Collections.unmodifiableCollection(skills.values());
    }

    /** 技能数量. */
    public int size() {
        return skills.size();
    }

    // ==================== 过滤 ====================

    /** 条件过滤. */
    public List<Skill> filter(Predicate<Skill> predicate) {
        return skills.values().stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    /** 按来源过滤. */
    public List<Skill> filterBySource(SkillSource source) {
        return filter(s -> s.source() == source);
    }

    /** 已启用技能列表. */
    public List<Skill> getEnabledSkills() {
        return filter(Skill::isEnabled);
    }

    /** 用户可调用技能列表. */
    public List<Skill> getUserInvocableSkills() {
        return filter(s -> s.isEnabled() && s.isUserInvocable());
    }
}
