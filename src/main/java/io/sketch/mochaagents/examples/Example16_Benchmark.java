package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.tool.impl.WebSearchTool;

import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example16 — 对应 smolagents 的 smolagents_benchmark/run.py.
 *
 * <p>演示并行 Agent 基准测试框架:
 * <ul>
 *   <li>数据集加载 (模拟 HuggingFace datasets: GAIA, MATH, SimpleQA)</li>
 *   <li>三种 Agent 模式: vanilla (纯 LLM), code (CodeAgent), tool-calling (ToolCallingAgent)</li>
 *   <li>ThreadPoolExecutor 并行评估</li>
 *   <li>结果保存为 JSONL（模拟 push_to_hub）</li>
 *   <li>Token 用量和耗时统计</li>
 * </ul>
 *
 * <pre>
 *   smolagents 对应:
 *     eval_ds = {task: datasets.load_dataset(eval_dataset, task, split="test") for task in tasks}
 *     with ThreadPoolExecutor(max_workers=parallel_workers) as exe:
 *         futures = [exe.submit(answer_single_question, ...) for example in examples_todo]
 *     append_answer(annotated_example, answers_file)
 * </pre>
 * @author lanxia39@163.com
 */
public final class Example16_Benchmark {

    // ─── Benchmark 数据集（模拟 GAIA / MATH / SimpleQA） ───

    record Question(String id, String question, String trueAnswer, String source) {}

    static final List<Question> GAIA_QUESTIONS = List.of(
            new Question("gaia-001", "What is the capital of France?", "Paris", "GAIA"),
            new Question("gaia-002", "How many continents are there on Earth?", "7", "GAIA"),
            new Question("gaia-003", "Who wrote 'Romeo and Juliet'?",
                    "William Shakespeare", "GAIA"),
            new Question("gaia-004", "What is the chemical symbol for water?",
                    "H2O", "GAIA")
    );

    static final List<Question> MATH_QUESTIONS = List.of(
            new Question("math-001", "What is 15 * 7 + 23?",
                    "128", "MATH"),
            new Question("math-002", "If x + 5 = 12, what is x?",
                    "7", "MATH"),
            new Question("math-003", "Calculate the area of a circle with radius 5.",
                    "78.54", "MATH")
    );

    static final List<Question> SIMPLEQA_QUESTIONS = List.of(
            new Question("sq-001", "What is the largest planet in our solar system?",
                    "Jupiter", "SimpleQA"),
            new Question("sq-002", "What year did World War II end?",
                    "1945", "SimpleQA"),
            new Question("sq-003", "What is the speed of light in km/s?",
                    "300000", "SimpleQA")
    );

    // ─── 基准测试结果 ───

    record BenchmarkResult(
            String modelId,
            String agentActionType,
            String question,
            String answer,
            String trueAnswer,
            String source,
            long durationMs,
            Map<String, Integer> tokenCounts,
            String startTime,
            String endTime
    ) {
        String toJsonLine() {
            return String.format(
                    "{\"model_id\":\"%s\",\"agent_action_type\":\"%s\"," +
                            "\"question\":\"%s\",\"answer\":\"%s\",\"true_answer\":\"%s\"," +
                            "\"source\":\"%s\",\"duration_ms\":%d," +
                            "\"token_counts\":{\"input\":%d,\"output\":%d}," +
                            "\"start_time\":\"%s\",\"end_time\":\"%s\"}",
                    escape(modelId), escape(agentActionType),
                    escape(question), escape(answer), escape(trueAnswer),
                    escape(source), durationMs,
                    tokenCounts.getOrDefault("input", 0),
                    tokenCounts.getOrDefault("output", 0),
                    escape(startTime), escape(endTime)
            );
        }

        private static String escape(String s) {
            if (s == null) return "null";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }
    }

    // ─── 并行任务执行器 ───

    static final class BenchmarkRunner {
        private final String modelId;
        private final String actionType; // vanilla / code / tool-calling
        private final int parallelWorkers;
        private final Path outputDir;
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger total;

        BenchmarkRunner(String modelId, String actionType, int parallelWorkers,
                        Path outputDir, int totalQuestions) {
            this.modelId = modelId;
            this.actionType = actionType;
            this.parallelWorkers = parallelWorkers;
            this.outputDir = outputDir;
            this.total = new AtomicInteger(totalQuestions);
        }

        /** 运行完整基准测试 */
        Map<String, List<BenchmarkResult>> run(Map<String, List<Question>> evalDs)
                throws Exception {
            Map<String, List<BenchmarkResult>> allResults = new LinkedHashMap<>();
            ExecutorService executor = Executors.newFixedThreadPool(parallelWorkers);

            try {
                for (var entry : evalDs.entrySet()) {
                    String task = entry.getKey();
                    List<Question> questions = entry.getValue();
                    System.out.println("\n┌─ Task: " + task + " (" + questions.size()
                            + " questions) ─┐");

                    // 跳过已回答的问题（断点续传模拟）
                    Path answersFile = outputDir.resolve(
                            modelId.replace('/', '_') + "__" + actionType
                                    + "__" + task + "__answers.jsonl");

                    List<BenchmarkResult> taskResults =
                            Collections.synchronizedList(new ArrayList<>());
                    List<Future<?>> futures = new ArrayList<>();

                    for (Question q : questions) {
                        futures.add(executor.submit(() -> {
                            BenchmarkResult result = answerSingleQuestion(q);
                            taskResults.add(result);
                            appendResult(result, answersFile);
                            int done = completed.incrementAndGet();
                            System.out.printf("  [%d/%d] %s → %s (took %dms)%n",
                                    done, total.get(),
                                    q.question().substring(0,
                                            Math.min(30, q.question().length())),
                                    result.answer().substring(0,
                                            Math.min(40, result.answer().length())),
                                    result.durationMs());
                        }));
                    }

                    // 等待所有完成
                    for (Future<?> f : futures) {
                        try {
                            f.get(120, TimeUnit.SECONDS);
                        } catch (TimeoutException e) {
                            System.out.println("  ⚠ Task timed out, continuing...");
                        }
                    }

                    allResults.put(task, taskResults);
                    System.out.println("└─ Task " + task + " complete: "
                            + taskResults.size() + " results ─┘");
                }
            } finally {
                executor.shutdown();
            }

            return allResults;
        }

        /** 回答单个问题 */
        private BenchmarkResult answerSingleQuestion(Question q) {
            Instant start = Instant.now();
            String startTime = LocalDateTime.now()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            // 增强问题
            String augmentedQuestion = q.question();
            if ("SimpleQA".equals(q.source())) {
                augmentedQuestion += " Answer with only the final answer.";
            }
            if ("MATH".equals(q.source())) {
                augmentedQuestion += " Write code, not latex.";
            }

            String answer;
            Map<String, Integer> tokens = new HashMap<>();

            try {
                switch (actionType) {
                    case "vanilla" -> {
                        // 纯 LLM 模式
                        tokens.put("input", 50);
                        tokens.put("output", 30);
                        answer = LLMFactory.create().complete(
                                io.sketch.mochaagents.llm.LLMRequest.builder()
                                        .prompt(augmentedQuestion).build()
                        ).content();
                    }
                    case "code" -> {
                        var registry = new ToolRegistry();
                        registry.register(new io.sketch.mochaagents.tool.impl.WebSearchTool());
                        registry.register(new io.sketch.mochaagents.tool.impl.WebFetchTool());
                        var agent = CodeAgent.builder()
                                .name("benchmark-code-agent")
                                .llm(LLMFactory.create())
                                .toolRegistry(registry)
                                .maxSteps(5)
                                .build();
                        tokens.put("input", 200);
                        tokens.put("output", 100);
                        answer = agent.run(augmentedQuestion);
                    }
                    case "tool-calling" -> {
                        var registry = new ToolRegistry();
                        registry.register(new io.sketch.mochaagents.tool.impl.WebSearchTool());
                        registry.register(new io.sketch.mochaagents.tool.impl.WebFetchTool());
                        var agent = ToolCallingAgent.builder()
                                .name("benchmark-tc-agent")
                                .llm(LLMFactory.create())
                                .toolRegistry(registry)
                                .maxSteps(5)
                                .build();
                        tokens.put("input", 180);
                        tokens.put("output", 90);
                        answer = agent.run(augmentedQuestion);
                    }
                    default -> answer = "Unknown action type: " + actionType;
                }
            } catch (Exception e) {
                answer = "Error: " + e.getMessage();
                tokens.put("input", 0);
                tokens.put("output", 0);
            }

            Instant end = Instant.now();
            String endTime = LocalDateTime.now()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            return new BenchmarkResult(
                    modelId, actionType,
                    augmentedQuestion, answer, q.trueAnswer(), q.source(),
                    Duration.between(start, end).toMillis(),
                    tokens, startTime, endTime
            );
        }
    }

    // ─── 结果保存 ───

    static void appendResult(BenchmarkResult result, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, result.toJsonLine() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write result: " + e.getMessage());
        }
    }

    // ─── 报告生成 ───

    static void printReport(Map<String, List<BenchmarkResult>> allResults) {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║          📊 Benchmark Report                         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        int totalCorrect = 0;
        int totalQuestions = 0;
        long totalDuration = 0;

        for (var taskEntry : allResults.entrySet()) {
            String task = taskEntry.getKey();
            List<BenchmarkResult> results = taskEntry.getValue();

            int correct = 0;
            long taskDuration = 0;
            for (BenchmarkResult r : results) {
                if (isAnswerCorrect(r.answer(), r.trueAnswer())) correct++;
                taskDuration += r.durationMs();
            }

            double accuracy = results.isEmpty() ? 0 :
                    (double) correct / results.size() * 100;
            totalCorrect += correct;
            totalQuestions += results.size();
            totalDuration += taskDuration;

            System.out.printf("%n┌─ %s ──────────────────────────────────────────────┐%n", task);
            System.out.printf("│  Accuracy    : %.1f%% (%d/%d)%n",
                    accuracy, correct, results.size());
            System.out.printf("│  Avg Time    : %d ms%n",
                    results.isEmpty() ? 0 : taskDuration / results.size());
            System.out.printf("│  Total Time  : %d ms%n", taskDuration);
            System.out.println("└──────────────────────────────────────────────────────┘");
        }

        double overallAccuracy = totalQuestions == 0 ? 0 :
                (double) totalCorrect / totalQuestions * 100;
        System.out.printf("%n┌─ Overall ───────────────────────────────────────────┐%n");
        System.out.printf("│  Accuracy    : %.1f%% (%d/%d)%n",
                overallAccuracy, totalCorrect, totalQuestions);
        System.out.printf("│  Total Time  : %d ms%n", totalDuration);
        System.out.println("└──────────────────────────────────────────────────────┘");

        // 比较不同 Agent 模式（如果运行了多种）
        System.out.println("\n💡 Tip: Compare different agent types with --agent-action-type option:");
        System.out.println("   - vanilla       : Pure LLM (baseline)");
        System.out.println("   - code          : CodeAgent with tools");
        System.out.println("   - tool-calling  : ToolCallingAgent with tools");
    }

    /** 简单的答案匹配 */
    private static boolean isAnswerCorrect(String answer, String trueAnswer) {
        if (answer == null || trueAnswer == null) return false;
        String a = answer.toLowerCase().trim();
        String t = trueAnswer.toLowerCase().trim();
        return a.contains(t) || t.contains(a);
    }

    // ─── main ───

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("Example16: Benchmark — 并行 Agent 基准测试框架");
        System.out.println("=".repeat(60));

        // 命令行参数模拟
        String modelId = "mock-model";
        String actionType = "code"; // vanilla / code / tool-calling
        int parallelWorkers = 4;
        String date = java.time.LocalDate.now().toString();
        Path outputDir = Path.of("output");

        System.out.println("\nConfiguration:");
        System.out.println("  Model ID      : " + modelId);
        System.out.println("  Action Type   : " + actionType);
        System.out.println("  Workers       : " + parallelWorkers);
        System.out.println("  Date          : " + date);
        System.out.println("  Output Dir    : " + outputDir.toAbsolutePath());

        // 加载评估数据集
        System.out.println("\n[1] Loading evaluation datasets...");
        Map<String, List<Question>> evalDs = new LinkedHashMap<>();
        evalDs.put("gaia", GAIA_QUESTIONS);
        evalDs.put("math", MATH_QUESTIONS);
        evalDs.put("simpleqa", SIMPLEQA_QUESTIONS);

        int totalQuestions = evalDs.values().stream().mapToInt(List::size).sum();
        System.out.println("    Tasks: " + evalDs.keySet());
        System.out.println("    Total questions: " + totalQuestions);

        // 运行基准测试
        System.out.println("\n[2] Running benchmark with " + parallelWorkers
                + " parallel workers...");
        var runner = new BenchmarkRunner(
                modelId, actionType, parallelWorkers, outputDir, totalQuestions);

        Instant benchStart = Instant.now();
        Map<String, List<BenchmarkResult>> allResults = runner.run(evalDs);
        long benchDuration = Duration.between(benchStart, Instant.now()).toMillis();

        // 打印报告
        printReport(allResults);
        System.out.printf("%nTotal benchmark time: %d ms%n", benchDuration);

        // 输出文件位置
        System.out.println("\n[3] Results saved to: " + outputDir.toAbsolutePath());
        try (var stream = Files.list(outputDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(p -> System.out.println("    📄 " + p.getFileName()));
        }

        // 清理输出文件
        try (var stream = Files.list(outputDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example16 Complete.");
    }

    private Example16_Benchmark() {}
}
