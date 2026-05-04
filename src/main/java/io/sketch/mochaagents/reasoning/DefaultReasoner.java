package io.sketch.mochaagents.reasoning;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.reasoning.strategy.ChainOfThought;
import io.sketch.mochaagents.reasoning.strategy.TreeOfThought;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认推理器 — Reasoner 接口的通用实现，支持可选策略链回退.
 *
 * <p>默认策略为 ChainOfThought。可注入多个策略实现回退:
 * 策略1 置信度低 → 尝试策略2 → ... → 返回最佳结果.
 * @author lanxia39@163.com
 */
public class DefaultReasoner implements Reasoner {

    private static final Logger log = LoggerFactory.getLogger(DefaultReasoner.class);
    private static final double CONFIDENCE_FALLBACK_THRESHOLD = 0.5;

    private final List<ReasoningStrategy> strategies;
    private int activeStrategyIdx;

    public DefaultReasoner(LLM llm) {
        this(List.of(
                new ChainOfThought(llm),
                new TreeOfThought(llm, 3, 2) // fallback: branch exploration when CoT confidence < 0.5
        ));
    }

    public DefaultReasoner(List<ReasoningStrategy> strategies) {
        if (strategies.isEmpty()) throw new IllegalArgumentException("At least one strategy required");
        this.strategies = new ArrayList<>(strategies);
        this.activeStrategyIdx = 0;
    }

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain best = ReasoningChain.empty();
        double bestConf = 0;

        for (int i = 0; i < strategies.size(); i++) {
            ReasoningStrategy strategy = strategies.get(i);
            try {
                ReasoningChain chain = strategy.reason(question);
                double conf = chain.averageConfidence();

                if (conf > bestConf) {
                    best = chain;
                    bestConf = conf;
                }

                if (conf >= CONFIDENCE_FALLBACK_THRESHOLD) {
                    log.debug("Strategy {} passed threshold (conf={})", i, String.format("%.2f", conf));
                    break;
                }
                log.debug("Strategy {} below threshold (conf={}), trying next", i, String.format("%.2f", conf));
            } catch (Exception e) {
                log.warn("Strategy {} failed: {}", i, e.getMessage());
            }
        }

        if (best.steps().isEmpty()) {
            best.add(new ReasoningStep(1, "Reason about: " + question,
                    "Unable to produce confident reasoning", 0.3));
        }

        return best;
    }

    @Override
    public void setStrategy(ReasoningStrategy strategy) {
        strategies.clear();
        strategies.add(strategy);
        activeStrategyIdx = 0;
    }

    @Override
    public ReasoningStrategy getStrategy() {
        return strategies.get(activeStrategyIdx);
    }

    /** 获取所有策略 */
    public List<ReasoningStrategy> getStrategies() { return List.copyOf(strategies); }

    /** 设置当前活跃策略索引 */
    public void setActiveStrategy(int idx) {
        if (idx < 0 || idx >= strategies.size())
            throw new IllegalArgumentException("Invalid strategy index: " + idx);
        this.activeStrategyIdx = idx;
    }
}
