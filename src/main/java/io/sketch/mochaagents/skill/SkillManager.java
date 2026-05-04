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
 * SkillManager init = SkillManager.bootstrap(toolRegistry);
 * // 后续可通过 init.skillRegistry() 查找技能
 * }</pre>
 * @author lanxia39@163.com
 */
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final SkillRegistry skillRegistry;
    private final FileSystemSkillLoader fileLoader;
    private final ToolRegistry toolRegistry;

    private SkillManager(ToolRegistry toolRegistry) {
        this.skillRegistry = new SkillRegistry();
        this.fileLoader = new FileSystemSkillLoader();
        this.toolRegistry = toolRegistry;
    }

    /**
     * 引导技能系统: 注册内置技能 + 加载文件系统技能 + 注册 SkillTool.
     *
     * @param toolRegistry 全局工具注册表
     * @return 初始化后的 SkillManager 实例
     */
    public static SkillManager bootstrap(ToolRegistry toolRegistry) {
        SkillManager init = new SkillManager(toolRegistry);
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
        registerSkill(BundledSkill.builder("commit",
                "Generate a concise git commit message for staged changes",
                SkillSource.BUNDLED)
                .prompt(args -> List.of(ContentBlock.text(
                        "Analyze the git diff and write a concise, "
                        + "conventional commit message (type: description). "
                        + "Focus on WHY, not WHAT. One-liner under 72 chars.")))
                .build());

        registerSkill(BundledSkill.builder("review",
                "Review code changes for bugs, style, and security issues",
                SkillSource.BUNDLED)
                .prompt(args -> List.of(ContentBlock.text(
                        "Review the following code diff. Identify: "
                        + "1) potential bugs or logic errors, "
                        + "2) security vulnerabilities, "
                        + "3) style/readability issues. Be specific and actionable.")))
                .build());

        registerSkill(BundledSkill.builder("explain",
                "Explain what a piece of code does in plain language",
                SkillSource.BUNDLED)
                .prompt(args -> List.of(ContentBlock.text(
                        "Explain the following code in clear, plain language. "
                        + "Describe what it does, how it works, "
                        + "and any notable patterns or edge cases.")))
                .build());

        registerSkill(BundledSkill.builder("bug-check",
                "Static analysis: check code for null safety, resource leaks, "
                + "SQL injection, infinite loops, hardcoded secrets, and more",
                SkillSource.BUNDLED)
                .prompt(args -> List.of(ContentBlock.text(
                        "Analyze the following code for bugs. Check for: "
                        + "1) null pointer dereferences, "
                        + "2) unclosed resources (files, streams, sockets), "
                        + "3) SQL/command injection vulnerabilities, "
                        + "4) empty catch blocks swallowing exceptions, "
                        + "5) hardcoded passwords or API keys, "
                        + "6) infinite loops without break conditions, "
                        + "7) race conditions or thread safety issues. "
                        + "Report each issue with severity (LOW/MEDIUM/HIGH/CRITICAL), line number, and fix suggestion.")))
                .build());

        registerSkill(BundledSkill.builder("refactor",
                "Refactor code for readability, performance, or design patterns",
                SkillSource.BUNDLED)
                .prompt(args -> List.of(ContentBlock.text(
                        "Refactor the following code. Focus on: "
                        + "1) readability and clarity, "
                        + "2) performance improvements, "
                        + "3) design pattern application, "
                        + "4) reducing complexity. "
                        + "Explain each change and why it improves the code.")))
                .build());

        registerSkill(BundledSkill.builder("test",
                "Generate unit tests for the given code",
                SkillSource.BUNDLED)
                .prompt(args -> List.of(ContentBlock.text(
                        "Generate comprehensive unit tests for the following code. "
                        + "Cover: 1) happy path, 2) edge cases (null, empty, boundary), "
                        + "3) error cases. Use JUnit 5 with AssertJ for assertions. "
                        + "Include descriptive test method names.")))
                .build());

        log.info("Registered {} bundled skills", skillRegistry.filterBySource(SkillSource.BUNDLED).size());
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
