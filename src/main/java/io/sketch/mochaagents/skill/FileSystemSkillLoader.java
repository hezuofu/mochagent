package io.sketch.mochaagents.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件系统技能加载器 — 对齐 claude-code 的 loadSkillsDir.ts.
 *
 * <p>从 .mocha/skills/ 目录加载 SKILL.md 文件，解析 YAML frontmatter 构建 Skill 实例。
 *
 * <p>目录结构:
 * <pre>
 * .mocha/skills/
 *   commit/
 *     SKILL.md        → 技能名 "commit"
 *   review/
 *     SKILL.md        → 技能名 "review"
 * </pre>
 *
 * <p>SKILL.md 格式:
 * <pre>
 * ---
 * name: commit
 * description: Generate conventional commits
 * allowed-tools: [bash, read, grep]
 * model: claude-sonnet-4-20250514
 * ---
 *
 * # Commit Skill
 * Instructions for generating commits...
 * </pre>
 *
 * <p>加载优先级（后注册覆盖先注册）:
 * <ol>
 *   <li>Managed path (.mocha/skills/)</li>
 *   <li>User home (~/.mocha/skills/)</li>
 *   <li>Project root (.mocha/skills/)</li>
 * </ol>
 */
public class FileSystemSkillLoader {

    private static final String SKILLS_DIR = ".mocha" + java.io.File.separator + "skills";
    private static final String SKILL_MD = "SKILL.md";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);

    // ==================== 公共 API ====================

    /**
     * 从指定基础目录递归加载技能.
     * 扫描 baseDir 下所有子目录中的 SKILL.md 文件。
     */
    public List<Skill> loadFromDirectory(Path baseDir) {
        if (!Files.isDirectory(baseDir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> entries = Files.list(baseDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(this::loadSkillFromDir)
                    .filter(s -> s != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            // Directory not accessible — return empty
            return Collections.emptyList();
        }
    }

    /**
     * 从多个基础目录加载技能.
     * 按顺序加载，后加载的覆盖先加载的同名技能。
     */
    public List<Skill> loadFromDirectories(List<Path> baseDirs) {
        Map<String, Skill> merged = new LinkedHashMap<>();
        for (Path dir : baseDirs) {
            for (Skill skill : loadFromDirectory(dir)) {
                merged.put(skill.name(), skill);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 加载默认位置的技能: user home、project root.
     */
    public List<Skill> loadDefaults() {
        List<Path> dirs = new ArrayList<>();

        // User home
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            dirs.add(Paths.get(userHome, SKILLS_DIR));
        }

        // Project root (current working directory)
        dirs.add(Paths.get(System.getProperty("user.dir", "."), SKILLS_DIR));

        return loadFromDirectories(dirs);
    }

    // ==================== 单技能加载 ====================

    /**
     * 从单个技能目录加载技能.
     * 读取 <skillDir>/SKILL.md，解析 frontmatter + markdown body。
     */
    public Skill loadSkillFromDir(Path skillDir) {
        Path skillFile = skillDir.resolve(SKILL_MD);
        if (!Files.isRegularFile(skillFile)) {
            return null;
        }

        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            return parseSkill(skillDir.getFileName().toString(), skillDir, content);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从 SKILL.md 内容解析技能定义.
     */
    Skill parseSkill(String dirName, Path skillDir, String content) {
        ParsedSkill parsed = parseFrontmatter(content, dirName);

        String promptBody = parsed.body;

        // Build base directory prefix
        String baseDir = skillDir.toAbsolutePath().toString();
        String promptText = "Base directory for this skill: " + baseDir + "\n\n" + promptBody;

        // Store for lambda capture
        final String finalPrompt = promptText;

        return BundledSkill.builder(parsed.name, parsed.description, SkillSource.FILE_SYSTEM)
                .whenToUse(parsed.whenToUse)
                .argumentHint(parsed.argumentHint)
                .version(parsed.version)
                .allowedTools(parsed.allowedTools)
                .model(parsed.model)
                .userInvocable(parsed.userInvocable)
                .context(parsed.context)
                .disableModelInvocation(parsed.disableModelInvocation)
                .prompt(args -> {
                    String text = finalPrompt;
                    if (args != null && !args.isEmpty()) {
                        text += "\n\n## Additional context from user\n\n" + args;
                    }
                    // Replace ${MOCHA_SKILL_DIR} with skill directory
                    text = text.replace("${MOCHA_SKILL_DIR}", baseDir);
                    return List.of(ContentBlock.text(text));
                })
                .build();
    }

    // ==================== Frontmatter 解析 ====================

    /**
     * 解析 SKILL.md 的 frontmatter 和 body.
     * 简单键值对解析器（不依赖第三方 YAML 库）。
     */
    static ParsedSkill parseFrontmatter(String content, String defaultName) {
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (!m.find()) {
            // No frontmatter — entire content is body, use directory name as skill name
            return new ParsedSkill(defaultName, content);
        }

        String frontmatterText = m.group(1);
        String body = m.group(2) != null ? m.group(2).stripLeading() : "";

        Map<String, Object> fm = parseYamlLike(frontmatterText);

        String name = getString(fm, "name", defaultName);
        String description = getString(fm, "description",
                extractFirstHeading(body, "Skill: " + name));
        String whenToUse = getString(fm, "when_to_use",
                getString(fm, "whenToUse", ""));
        String argumentHint = getString(fm, "argument_hint",
                getString(fm, "argumentHint", ""));
        String version = getString(fm, "version", "");
        String model = getString(fm, "model", null);
        boolean userInvocable = getBoolean(fm, "user-invocable",
                getBoolean(fm, "userInvocable", true));
        boolean disableModelInvocation = getBoolean(fm, "disable-model-invocation",
                getBoolean(fm, "disableModelInvocation", false));

        List<String> allowedTools = getStringList(fm, "allowed-tools",
                getStringList(fm, "allowedTools", Collections.emptyList()));

        SkillContext context = parseContext(getString(fm, "context", "inline"));

        return new ParsedSkill(name, description, whenToUse, argumentHint,
                version, model, allowedTools, userInvocable,
                disableModelInvocation, context, body);
    }

    // ==================== YAML-Like 简单解析器 ====================

    /**
     * 简化版 YAML 键值对解析.
     * 支持格式:
     *   key: value
     *   key: "value with spaces"
     *   key: [item1, item2]
     *   key: true / false
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseYamlLike(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] lines = text.split("\\n");

        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();

        for (String line : lines) {
            // Skip empty lines and comments
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // Check if this is a new key: value
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0 && !line.substring(0, colonIdx).trim().contains(" ")) {
                // Flush previous key
                if (currentKey != null) {
                    result.put(currentKey, parseYamlValue(currentValue.toString().trim()));
                }
                currentKey = line.substring(0, colonIdx).trim();
                currentValue = new StringBuilder(line.substring(colonIdx + 1));
            } else if (currentKey != null) {
                // Continuation of previous value (multi-line, indented)
                currentValue.append('\n').append(line);
            }
        }

        // Flush last key
        if (currentKey != null) {
            result.put(currentKey, parseYamlValue(currentValue.toString().trim()));
        }

        return result;
    }

    /** 解析 YAML 值：字符串、列表、布尔. */
    static Object parseYamlValue(String value) {
        if (value.isEmpty()) {
            return "";
        }
        // Quoted string
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        // List: [a, b, c]
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1).trim();
            if (inner.isEmpty()) return Collections.emptyList();
            return Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .map(s -> s.replaceAll("^[\"']|[\"']$", ""))
                    .collect(Collectors.toList());
        }
        // Boolean
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;

        return value;
    }

    // ==================== 辅助方法 ====================

    static String getString(Map<String, Object> fm, String key, String defaultVal) {
        Object v = fm.get(key);
        return v instanceof String ? (String) v : defaultVal;
    }

    @SuppressWarnings("unchecked")
    static List<String> getStringList(Map<String, Object> fm, String key, List<String> defaultVal) {
        Object v = fm.get(key);
        if (v instanceof List) return (List<String>) v;
        return defaultVal;
    }

    static boolean getBoolean(Map<String, Object> fm, String key, boolean defaultVal) {
        Object v = fm.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return defaultVal;
    }

    static SkillContext parseContext(String value) {
        if ("fork".equalsIgnoreCase(value)) return SkillContext.FORK;
        return SkillContext.INLINE;
    }

    /** 提取第一个 Markdown 标题. */
    static String extractFirstHeading(String body, String defaultDescription) {
        if (body == null || body.isEmpty()) return defaultDescription;
        Pattern heading = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
        Matcher m = heading.matcher(body);
        return m.find() ? m.group(1).trim() : defaultDescription;
    }

    // ==================== 内部数据类 ====================

    /** 解析后的技能数据（不可变）. */
    record ParsedSkill(
            String name,
            String description,
            String whenToUse,
            String argumentHint,
            String version,
            String model,
            List<String> allowedTools,
            boolean userInvocable,
            boolean disableModelInvocation,
            SkillContext context,
            String body
    ) {
        ParsedSkill(String name, String body) {
            this(name, "Skill: " + name, "", "", "", null,
                    Collections.emptyList(), true, false, SkillContext.INLINE, body);
        }
    }
}
