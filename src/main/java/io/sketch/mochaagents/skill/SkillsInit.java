package io.sketch.mochaagents.skill;

import io.sketch.mochaagents.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 技能系统初始化入口 — 对齐 claude-code 的 initBundledSkills() + getSkillDirCommands().
 *
 * <p>在框架启动时调用，完成:
 * <ol>
 *   <li>创建全局 SkillRegistry</li>
 *   <li>注册内置 (bundled) 技能</li>
 *   <li>从文件系统加载技能</li>
 *   <li>在 ToolRegistry 中注册 SkillTool 桥接器</li>
 * </ol>
 *
 * <p>典型用法:
 * <pre>{@code
 * SkillsInit init = SkillsInit.bootstrap(toolRegistry);
 * // 后续可通过 init.skillRegistry() 查找技能
 * }</pre>
 * @author lanxia39@163.com
 */
public class SkillsInit {

    private static final Logger log = LoggerFactory.getLogger(SkillsInit.class);

    private final SkillRegistry skillRegistry;
    private final FileSystemSkillLoader fileLoader;
    private final ToolRegistry toolRegistry;

    private SkillsInit(ToolRegistry toolRegistry) {
        this.skillRegistry = new SkillRegistry();
        this.fileLoader = new FileSystemSkillLoader();
        this.toolRegistry = toolRegistry;
    }

    /**
     * 引导技能系统: 注册内置技能 + 加载文件系统技能 + 注册 SkillTool.
     *
     * @param toolRegistry 全局工具注册表
     * @return 初始化后的 SkillsInit 实例
     */
    public static SkillsInit bootstrap(ToolRegistry toolRegistry) {
        SkillsInit init = new SkillsInit(toolRegistry);
        init.registerBundledSkills();
        init.loadFileSystemSkills();
        init.registerSkillTool();
        log.info("Skills system initialized: {} skills loaded ({} bundled, {} file-system)",
                init.skillRegistry.size(),
                init.skillRegistry.filterBySource(SkillSource.BUNDLED).size(),
                init.skillRegistry.filterBySource(SkillSource.FILE_SYSTEM).size());
        return init;
    }

    /** 获取全局技能注册表. */
    public SkillRegistry skillRegistry() {
        return skillRegistry;
    }

    /** 获取文件系统加载器. */
    public FileSystemSkillLoader fileLoader() {
        return fileLoader;
    }

    // ==================== 内置技能注册 ====================

    /**
     * 注册所有内置技能.
     * 参考 claude-code 的 bundled/index.ts initBundledSkills() 模式。
     * 新增内置技能在此方法中添加。
     */
    private void registerBundledSkills() {
        // 预留：后续可添加内置技能
        // registerSkill(BundledSkill.builder("commit", "...", SkillSource.BUNDLED)...build());
        // registerSkill(BundledSkill.builder("review-pr", "...", SkillSource.BUNDLED)...build());

        log.debug("Registered {} bundled skills", skillRegistry.filterBySource(SkillSource.BUNDLED).size());
    }

    /** 便捷注册单个技能. */
    public void registerSkill(Skill skill) {
        skillRegistry.register(skill);
    }

    // ==================== 文件系统技能加载 ====================

    /**
     * 从文件系统加载技能.
     * 扫描 user home 和 project root 的 .mocha/skills/ 目录。
     */
    private void loadFileSystemSkills() {
        List<Skill> fileSkills = fileLoader.loadDefaults();
        skillRegistry.registerAll(fileSkills);
        log.debug("Loaded {} skills from file system", fileSkills.size());
    }

    /**
     * 重新加载文件系统技能（热加载）.
     */
    public void reloadFileSystemSkills() {
        // Remove existing file-system skills
        List<Skill> existing = skillRegistry.filterBySource(SkillSource.FILE_SYSTEM);
        for (Skill s : existing) {
            skillRegistry.unregister(s.name());
        }
        // Reload
        loadFileSystemSkills();
        log.info("File-system skills reloaded");
    }

    // ==================== SkillTool 桥接 ====================

    /**
     * 向 ToolRegistry 注册 SkillTool.
     * 使 LLM 可以通过 Skill 工具调用技能。
     */
    private void registerSkillTool() {
        toolRegistry.registerSkillTool(skillRegistry);
        log.debug("SkillTool registered in ToolRegistry");
    }
}
