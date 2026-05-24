// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.skill;

import io.sketch.mochaagents.plugin.ExtensionPoint;
import io.sketch.mochaagents.plugin.Plugin;

import java.util.Collections;
import java.util.List;

/**
 * Skill — a prompt template loaded via the unified {@link Plugin} system.
 *
 * <p>Skills are registered as ExtensionPoint("SKILL", ...) and discovered
 * by the PluginLoader alongside other plugins.
 *
 * @author lanxia39@163.com
 */
public interface Skill extends Plugin {

    String name();
    default String description() { return ""; }
    default List<String> aliases() { return Collections.emptyList(); }
    default String whenToUse() { return ""; }
    default String argumentHint() { return ""; }
    default String version() { return ""; }
    default SkillSource source() { return SkillSource.FILE_SYSTEM; }
    default boolean isEnabled() { return true; }
    default boolean isUserInvocable() { return true; }
    default SkillContext context() { return SkillContext.INLINE; }
    default List<String> allowedTools() { return Collections.emptyList(); }
    default String model() { return null; }
    default boolean disableModelInvocation() { return false; }
    List<ContentBlock> getPromptForCommand(String args);

    default List<ExtensionPoint<?>> extensions() {
        return List.of(ExtensionPoint.skill(this, 0));
    }
}
