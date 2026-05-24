// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import io.sketch.mochaagents.model.Model;
import java.util.List;

/**
 * 降级策略 — 主 Model 不可用时自动切换到备用 Model.
 * @author lanxia39@163.com
 */
public class FallbackStrategy {

    private final int maxRetries;

    public FallbackStrategy(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public FallbackStrategy() {
        this(3);
    }

    /**
     * 从备用列表选择降级 Model.
     */
    public Model fallback(Model failed, List<Model> alternatives) {
        for (Model alt : alternatives) {
            if (alt != failed) return alt;
        }
        throw new IllegalStateException("No fallback Model available");
    }
}
