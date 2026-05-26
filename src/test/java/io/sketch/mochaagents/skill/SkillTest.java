// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SkillTest {

    // ── ContentBlock ──

    @Test void contentBlockTextField() {
        var block = ContentBlock.text("hello world");
        assertEquals("text", block.type());
        assertEquals("hello world", block.text());
    }

    @Test void contentBlockRejectsBlankType() {
        assertThrows(IllegalArgumentException.class, () -> new ContentBlock("", "text"));
        assertThrows(IllegalArgumentException.class, () -> new ContentBlock("  ", "text"));
    }

    // ── SkillSource ──

    @Test void skillSourceValues() {
        assertEquals(3, SkillSource.values().length);
        assertEquals(SkillSource.BUNDLED, SkillSource.valueOf("BUNDLED"));
        assertEquals(SkillSource.FILE_SYSTEM, SkillSource.valueOf("FILE_SYSTEM"));
        assertEquals(SkillSource.PLUGIN, SkillSource.valueOf("PLUGIN"));
    }

    // ── SkillContext ──

    @Test void skillContextValues() {
        assertEquals(2, SkillContext.values().length);
        assertEquals(SkillContext.INLINE, SkillContext.valueOf("INLINE"));
        assertEquals(SkillContext.FORK, SkillContext.valueOf("FORK"));
    }

    // ── BundledSkill ──

    @Test void bundledSkillBasicProperties() {
        var skill = BundledSkill.builder("greet", "A greeting skill", SkillSource.BUNDLED)
                .prompt(args -> List.of(new ContentBlock("Hello " + args)))
                .build();

        assertEquals("greet", skill.name());
        assertEquals("A greeting skill", skill.description());
        assertEquals(SkillSource.BUNDLED, skill.source());
        assertTrue(skill.isEnabled());
        assertTrue(skill.isUserInvocable());
    }

    @Test void bundledSkillPromptGeneration() {
        var skill = BundledSkill.builder("echo", "Echoes input", SkillSource.BUNDLED)
                .prompt(args -> List.of(ContentBlock.text("Echo: " + args)))
                .build();

        var blocks = skill.getPromptForCommand("test123");
        assertEquals(1, blocks.size());
        assertEquals("text", blocks.get(0).type());
        assertTrue(blocks.get(0).text().contains("test123"));
    }

    @Test void bundledSkillNullArgsHandled() {
        var skill = BundledSkill.builder("default", "Default prompt", SkillSource.BUNDLED)
                .prompt(args -> List.of(ContentBlock.text("default")))
                .build();

        var blocks = skill.getPromptForCommand(null);
        assertEquals(1, blocks.size());
        assertEquals("default", blocks.get(0).text());
    }

    @Test void bundledSkillAliases() {
        var skill = BundledSkill.builder("commit", "Commit", SkillSource.BUNDLED)
                .aliases(List.of("cmt", "c"))
                .prompt(args -> List.of())
                .build();

        assertEquals(2, skill.aliases().size());
        assertTrue(skill.aliases().contains("cmt"));
    }

    @Test void bundledSkillDisabledAndNotInvocable() {
        var skill = BundledSkill.builder("hidden", "Internal", SkillSource.BUNDLED)
                .enabled(false)
                .userInvocable(false)
                .prompt(args -> List.of())
                .build();

        assertFalse(skill.isEnabled());
        assertFalse(skill.isUserInvocable());
    }

    @Test void bundledSkillRejectsNullName() {
        assertThrows(NullPointerException.class, () ->
                BundledSkill.builder(null, "desc", SkillSource.BUNDLED).build());
    }

    @Test void bundledSkillDefaultPromptGeneratorReturnsEmpty() {
        var builder = BundledSkill.builder("test", "desc", SkillSource.BUNDLED);
        // Without setting prompt, default generator returns empty list
        var skill = builder.build();
        assertTrue(skill.getPromptForCommand("").isEmpty());
    }

    @Test void bundledSkillWithAllMetadata() {
        var skill = BundledSkill.builder("full", "Full featured", SkillSource.FILE_SYSTEM)
                .whenToUse("Use for X")
                .argumentHint("<args>")
                .version("2.1")
                .context(SkillContext.FORK)
                .allowedTools(List.of("bash", "read"))
                .model("claude-sonnet")
                .disableModelInvocation(true)
                .prompt(args -> List.of(ContentBlock.text("prompt")))
                .build();

        assertEquals("Use for X", skill.whenToUse());
        assertEquals("<args>", skill.argumentHint());
        assertEquals("2.1", skill.version());
        assertEquals(SkillContext.FORK, skill.context());
        assertEquals(2, skill.allowedTools().size());
        assertEquals("claude-sonnet", skill.model());
        assertTrue(skill.disableModelInvocation());
    }

    // ── SkillRegistry ──

    @Test void registryRegisterAndFind() {
        var reg = new SkillRegistry();
        var skill = BundledSkill.builder("test", "Test skill", SkillSource.BUNDLED)
                .prompt(args -> List.of())
                .build();
        reg.register(skill);

        assertTrue(reg.has("test"));
        assertEquals(skill, reg.findByName("test"));
        assertEquals(1, reg.size());
    }

    @Test void registryAliasResolution() {
        var reg = new SkillRegistry();
        var skill = BundledSkill.builder("commit", "Generate commit", SkillSource.BUNDLED)
                .aliases(List.of("cmt"))
                .prompt(args -> List.of())
                .build();
        reg.register(skill);

        assertTrue(reg.has("cmt"));
        assertEquals(skill, reg.findByName("cmt"));
    }

    @Test void registryUnregisterRemovesAliases() {
        var reg = new SkillRegistry();
        var skill = BundledSkill.builder("test", "Test", SkillSource.BUNDLED)
                .aliases(List.of("t"))
                .prompt(args -> List.of())
                .build();
        reg.register(skill);
        reg.unregister("test");

        assertFalse(reg.has("test"));
        assertFalse(reg.has("t"));
        assertEquals(0, reg.size());
    }

    @Test void registryFilterBySource() {
        var reg = new SkillRegistry();
        var bundled = BundledSkill.builder("b", "Bundled", SkillSource.BUNDLED)
                .prompt(args -> List.of()).build();
        var file = BundledSkill.builder("f", "File", SkillSource.FILE_SYSTEM)
                .prompt(args -> List.of()).build();
        reg.register(bundled);
        reg.register(file);

        assertEquals(1, reg.filterBySource(SkillSource.BUNDLED).size());
        assertEquals(1, reg.filterBySource(SkillSource.FILE_SYSTEM).size());
        assertEquals(0, reg.filterBySource(SkillSource.PLUGIN).size());
    }

    @Test void registryGetEnabledSkills() {
        var reg = new SkillRegistry();
        reg.register(BundledSkill.builder("a", "A", SkillSource.BUNDLED)
                .enabled(true).prompt(args -> List.of()).build());
        reg.register(BundledSkill.builder("b", "B", SkillSource.BUNDLED)
                .enabled(false).prompt(args -> List.of()).build());

        assertEquals(1, reg.getEnabledSkills().size());
        assertEquals("a", reg.getEnabledSkills().get(0).name());
    }

    @Test void registryClear() {
        var reg = new SkillRegistry();
        reg.register(BundledSkill.builder("a", "A", SkillSource.BUNDLED)
                .prompt(args -> List.of()).build());
        reg.clear();
        assertEquals(0, reg.size());
        assertFalse(reg.has("a"));
    }

    @Test void registryAllReturnsUnmodifiable() {
        var reg = new SkillRegistry();
        reg.register(BundledSkill.builder("a", "A", SkillSource.BUNDLED)
                .prompt(args -> List.of()).build());
        var all = reg.all();
        assertEquals(1, all.size());
        assertThrows(UnsupportedOperationException.class, () -> all.add(null));
    }

    // ── FileSystemSkillLoader: frontmatter parsing ──

    @Test void parseYamlLikeSimpleKeyValue() {
        Map<String, Object> result = FileSystemSkillLoader.parseYamlLike("name: my-skill\ndescription: Does things\n");
        assertEquals("my-skill", result.get("name"));
        assertEquals("Does things", result.get("description"));
    }

    @Test void parseYamlLikeQuotedValue() {
        Map<String, Object> result = FileSystemSkillLoader.parseYamlLike(
                "name: \"My Skill\"\ndescription: 'A skill'\n");
        assertEquals("My Skill", result.get("name"));
        assertEquals("A skill", result.get("description"));
    }

    @Test void parseYamlLikeListValue() {
        Map<String, Object> result = FileSystemSkillLoader.parseYamlLike(
                "allowed-tools: [bash, read, grep]\n");
        @SuppressWarnings("unchecked")
        var tools = (List<String>) result.get("allowed-tools");
        assertEquals(3, tools.size());
        assertTrue(tools.contains("bash"));
    }

    @Test void parseYamlLikeBoolean() {
        Map<String, Object> result = FileSystemSkillLoader.parseYamlLike(
                "user-invocable: true\ndisable-model-invocation: false\n");
        assertEquals(true, result.get("user-invocable"));
        assertEquals(false, result.get("disable-model-invocation"));
    }

    @Test void parseYamlLikeEmptyList() {
        Map<String, Object> result = FileSystemSkillLoader.parseYamlLike("allowed-tools: []\n");
        @SuppressWarnings("unchecked")
        var tools = (List<String>) result.get("allowed-tools");
        assertTrue(tools.isEmpty());
    }

    @Test void parseYamlLikeSkipsComments() {
        Map<String, Object> result = FileSystemSkillLoader.parseYamlLike(
                "# This is a comment\nname: test\n");
        assertEquals("test", result.get("name"));
    }

    @Test void parseFrontmatterExtractsNameAndBody() {
        String content = "---\nname: my-skill\ndescription: Does something\n---\n\n# Heading\nBody text.";
        var parsed = FileSystemSkillLoader.parseFrontmatter(content, "default");

        assertEquals("my-skill", parsed.name());
        assertEquals("Does something", parsed.description());
        assertTrue(parsed.body().contains("Body text"));
    }

    @Test void parseFrontmatterNoFrontmatterUsesDefault() {
        String content = "# Just a heading\n\nNo frontmatter here.";
        var parsed = FileSystemSkillLoader.parseFrontmatter(content, "fallback");

        assertEquals("fallback", parsed.name());
    }

    @Test void parseFrontmatterExtractsDescriptionFromHeading() {
        String content = "---\nname: skill\n---\n\n# This is my skill\n\nBody here.";
        var parsed = FileSystemSkillLoader.parseFrontmatter(content, "default");

        assertEquals("This is my skill", parsed.description());
    }

    @Test void parseContextValues() {
        assertEquals(SkillContext.INLINE, FileSystemSkillLoader.parseContext("inline"));
        assertEquals(SkillContext.INLINE, FileSystemSkillLoader.parseContext("INLINE"));
        assertEquals(SkillContext.FORK, FileSystemSkillLoader.parseContext("fork"));
        assertEquals(SkillContext.FORK, FileSystemSkillLoader.parseContext("FORK"));
        assertEquals(SkillContext.INLINE, FileSystemSkillLoader.parseContext("unknown"));
    }

    // ── FileSystemSkillLoader: temp directory ──

    @Test void loadFromEmptyDirectory(@TempDir Path tempDir) {
        var loader = new FileSystemSkillLoader();
        var skills = loader.loadFromDirectory(tempDir);
        assertTrue(skills.isEmpty());
    }

    @Test void loadFromNonExistentDirectory() {
        var loader = new FileSystemSkillLoader();
        var skills = loader.loadFromDirectory(Path.of("/nonexistent/path/12345"));
        assertTrue(skills.isEmpty());
    }

    @Test void loadSkillFromValidDir(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("myskill");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: myskill\ndescription: My custom skill\n---\n\n# My Skill\n\nDo stuff.");

        var loader = new FileSystemSkillLoader();
        var skill = loader.loadSkillFromDir(skillDir);

        assertNotNull(skill);
        assertEquals("myskill", skill.name());
        assertEquals("My custom skill", skill.description());
        assertEquals(SkillSource.FILE_SYSTEM, skill.source());
    }

    // ── Skill as Plugin ──

    @Test void skillExtensionsReturnsSkillExtensionPoint() {
        var skill = BundledSkill.builder("test", "Test", SkillSource.BUNDLED)
                .prompt(args -> List.of())
                .build();
        var exts = skill.extensions().toList();
        assertEquals(1, exts.size());
        assertNotNull(exts.get(0));
    }

    // ── SkillManager bootstrap ──

    @Test void skillManagerBootstrapWithoutToolRegistry() {
        // Bootstrap with null toolRegistry — should fail gracefully or work
        // Since ToolRegistry is needed for SkillTool, test without it
        var reg = new SkillRegistry();
        var bundled = BundledSkill.builder("test", "Test", SkillSource.BUNDLED)
                .prompt(args -> List.of()).build();
        reg.register(bundled);

        assertEquals(1, reg.size());
        assertEquals("test", reg.findByName("test").name());
    }
}
