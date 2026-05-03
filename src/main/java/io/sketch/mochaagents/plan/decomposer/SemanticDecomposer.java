package io.sketch.mochaagents.plan.decomposer;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.plan.PlanStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义分解器 — 用 LLM 理解任务语义，生成带依赖关系的子步骤.
 * @author lanxia39@163.com
 */
public class SemanticDecomposer implements TaskDecomposer {

    private static final Logger log = LoggerFactory.getLogger(SemanticDecomposer.class);
    private final LLM llm;

    public SemanticDecomposer(LLM llm) { this.llm = llm; }

    @Override
    public List<PlanStep> decompose(String task, int maxSteps) {
        String prompt = String.format("""
                Decompose this task into at most %d sub-steps with dependencies.
                Task: %s
                Output one per line:
                - <step description> | depends: <step_numbers or none>""", maxSteps, task);

        String response = llm.complete(LLMRequest.builder()
                .addMessage("user", prompt).maxTokens(1024).temperature(0.3).build()).content();

        List<PlanStep> steps = new ArrayList<>();
        for (String line : response.split("\n")) {
            line = line.trim();
            if (!line.startsWith("- ") && !line.matches("\\d+[.)]\\s+.*")) continue;

            String cleaned = line.replaceFirst("^[-\\d.)]+\\s*", "");
            String[] parts = cleaned.split("\\s*\\|\\s*depends?\\s*[:：]\\s*", 2);
            String desc = parts[0].trim();
            if (desc.isEmpty()) continue;

            List<String> deps = new ArrayList<>();
            if (parts.length > 1 && !parts[1].trim().equalsIgnoreCase("none")) {
                for (String d : parts[1].trim().split("[,\\s]+")) {
                    if (!d.isBlank()) deps.add(d.matches("\\d+") ? "step-" + d : d);
                }
            }

            steps.add(PlanStep.builder()
                    .stepId("step-" + (steps.size() + 1))
                    .description(desc)
                    .dependencies(deps)
                    .build());
        }

        if (steps.isEmpty()) {
            steps.add(PlanStep.builder().stepId("step-1").description(task).build());
        }

        log.debug("SemanticDecomposer: {} steps", steps.size());
        return steps;
    }
}
