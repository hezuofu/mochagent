// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.event;

import java.lang.annotation.*;

/**
 * Marks a method as an event subscriber (Guava EventBus pattern).
 *
 * <p>The method must have exactly one parameter — the event type it handles.
 * Dispatch is type-safe: a handler for {@code StepCompleted} receives only
 * {@code StepCompleted} events (including subtypes).
 *
 * <pre>{@code
 * class MyHandler {
 *     &#64;Subscribe
 *     void onStep(StepCompleted e) { ... }
 *
 *     &#64;Subscribe
 *     void onFile(FileModified e) { ... }
 * }
 * bus.register(new MyHandler());
 * bus.post(new StepCompleted("agent", 3, "output", "obs"));
 * }</pre>
 *
 * @author lanxia39@163.com
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
    /** If true, the handler runs on the posting thread (sync). Default: async on EventBus executor. */
    boolean sync() default false;
}
