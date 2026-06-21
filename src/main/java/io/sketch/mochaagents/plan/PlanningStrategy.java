// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plan;

/**
 * PlanningStrategy strategy interface.
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface PlanningStrategy {

    Plan<?> plan(PlanningRequest<?> request);
    default String name() { return getClass().getSimpleName(); }
}
