// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative plugin metadata — discovered without instantiation.
 *
 * @author lanxia39@163.com
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginMeta {
    String name();
    String description() default "";
    String version() default "1.0";
}
