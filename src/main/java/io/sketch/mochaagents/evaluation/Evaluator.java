package io.sketch.mochaagents.evaluation;

/**
 * 评估器接口 — Agent 输出质量评估的核心抽象.
 * @author lanxia39@163.com
 */
public interface Evaluator {

    /** 评估 Agent 输出 */
    EvaluationResult evaluate(String input, String output, String expected);

    /** 获取评估标准 */
    EvaluationCriteria getCriteria();
}
