package io.sketch.mochaagents.reasoning.strategy;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.ReasoningStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Tree of Thought — 让 LLM 生成多个候选思路分支，评分并选择最优路径展开.
 * @author lanxia39@163.com
 */
public class TreeOfThought implements ReasoningStrategy {

    private static final Logger log = LoggerFactory.getLogger(TreeOfThought.class);
    private final LLM llm;
    private final int numBranches;
    private final int maxDepth;

    public TreeOfThought(LLM llm) { this(llm, 3, 2); }

    public TreeOfThought(LLM llm, int numBranches, int maxDepth) {
        this.llm = llm; this.numBranches = numBranches; this.maxDepth = maxDepth;
    }

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain chain = new ReasoningChain();
        chain.add(new ReasoningStep(0, "Root: " + question,
                "Explore " + numBranches + " branches, max depth " + maxDepth, 1.0));

        List<Branch> branches = generateBranches(question, "", 0);
        for (int depth = 1; depth <= maxDepth && !branches.isEmpty(); depth++) {
            Branch best = selectBest(branches);
            chain.add(new ReasoningStep(depth, best.thought,
                    "score=" + String.format("%.2f", best.score), best.score));
            if (best.score > 0.9 || depth == maxDepth) {
                chain.add(new ReasoningStep(depth + 1, "Selected: " + best.thought,
                        best.conclusion, best.score));
                break;
            }
            branches = generateBranches(best.thought, best.thought, depth);
        }
        log.debug("TreeOfThought: {} steps, avg confidence={}",
                chain.steps().size(), chain.averageConfidence());
        return chain;
    }

    private List<Branch> generateBranches(String context, String path, int depth) {
        String prompt = """
                Given: %s
                Previous path: %s
                Generate %d alternative reasoning paths.
                Branch N: <thought>
                Conclusion: <expected outcome>
                Score: <0.0-1.0>""".formatted(context, path.isEmpty() ? "(none)" : path, numBranches);

        String text = llm.complete(LLMRequest.builder()
                .addMessage("user", prompt).maxTokens(1024).temperature(0.7).build()).content();
        return parseBranches(text);
    }

    private List<Branch> parseBranches(String text) {
        List<Branch> out = new ArrayList<>();
        for (String part : text.split("(?=Branch\\s*\\d+)")) {
            if (part.isBlank()) continue;
            String thought = extractField(part, "Branch\\s*\\d+[:：]\\s*", "");
            String conclusion = extractField(part, "Conclusion[:：]\\s*", thought);
            double score = extractScore(part);
            out.add(new Branch(thought.trim(), conclusion.trim(), score));
        }
        if (out.isEmpty()) out.add(new Branch(text.trim(), "default", 0.5));
        return out;
    }

    private Branch selectBest(List<Branch> list) {
        Branch best = list.get(0);
        for (Branch b : list) if (b.score > best.score) best = b;
        list.remove(best);
        return best;
    }

    private String extractField(String text, String regex, String fallback) {
        var m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        int start = m.find() ? m.end() : 0;
        String[] lines = text.substring(start).trim().split("\n", 2);
        return lines.length > 0 ? lines[0].trim() : fallback;
    }

    private double extractScore(String text) {
        var m = java.util.regex.Pattern.compile("Score\\s*[:：]\\s*([\\d.]+)").matcher(text);
        if (m.find()) {
            try { return Math.min(1.0, Math.max(0.0, Double.parseDouble(m.group(1)))); }
            catch (NumberFormatException e) {}
        }
        return 0.5;
    }

    private record Branch(String thought, String conclusion, double score) {}
}
