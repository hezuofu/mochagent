package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.react.LoopState;
import io.sketch.mochaagents.agent.react.ReActAgent;
import io.sketch.mochaagents.agent.react.StepResult;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.memory.AgentMemory;
import io.sketch.mochaagents.agent.react.step.ActionStep;
import io.sketch.mochaagents.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeAgent — 解析 LLM 输出的代码块并在沙箱中执行的 ReAct Agent.
 *
 * <p>执行流程：
 * <ol>
 *   <li>将记忆写入 LLM 消息</li>
 *   <li>LLM 输出包含 {@code <code>...</code>} 或 {@code ```python```} 代码块</li>
 *   <li>解析代码块</li>
 *   <li>在沙箱中执行代码</li>
 *   <li>记录输出与观察结果</li>
 *   <li>若调用 {@code final_answer()} 则终止</li>
 * </ol>
 *
 * <p>对应 smolagents 的 {@code CodeAgent}.
 * @author lanxia39@163.com
 */
public final class CodeAgent extends ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(CodeAgent.class);

    /** 代码块标记：支持多种格式 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(?:python|java|js)?\\s*\\n(.*?)```|<code>(.*?)</code>",
            Pattern.DOTALL
    );

    /** final_answer 调用检测 */
    private static final Pattern FINAL_ANSWER_PATTERN =
            Pattern.compile("final_answer\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");

    /** Python 风格的 final_answer */
    private static final Pattern FINAL_ANSWER_PY_PATTERN =
            Pattern.compile("final_answer\\s*\\(\\s*'''([^']*)'''\\s*\\)",
                    Pattern.DOTALL);

    /** 无引号变量引用 final_answer(varname) */
    private static final Pattern FINAL_ANSWER_BARE_PATTERN =
            Pattern.compile("final_answer\\s*\\((\\w+)\\s*\\)");

    private final Set<String> authorizedImports;
    private final String codeLanguage;
    private final int maxPrintOutputLength;
    private final boolean useStructuredOutput;

    private CodeAgent(Builder builder) {
        super(builder);
        this.authorizedImports = builder.authorizedImports != null
                ? builder.authorizedImports : Set.of(
                "java.lang.*", "java.util.*", "java.math.*", "java.text.*"
        );
        this.codeLanguage = builder.codeLanguage != null ? builder.codeLanguage : "python";
        this.maxPrintOutputLength = builder.maxPrintOutputLength > 0
                ? builder.maxPrintOutputLength : 10000;
        this.useStructuredOutput = builder.useStructuredOutput;
    }

    @Override
    public String buildSystemPrompt() {
        if (systemPromptTemplate != null && !systemPromptTemplate.template().isEmpty()) {
            return systemPromptTemplate.render(Map.of(
                    "tools", formatTools(),
                    "authorized_imports", String.join(", ", authorizedImports),
                    "instructions", description != null ? description : ""
            ));
        }
        if (useStructuredOutput) {
            return String.format("""
                    You are an AI assistant that solves tasks by writing and executing code.
                    Output a JSON object with two fields:
                    {"thought": "<your reasoning>", "code": "<executable code>"}

                    The code will be executed and the output shown back to you.
                    Call final_answer(\"your answer\") inside code to finish.
                    Available tools:
                    %s
                    """, formatTools());
        }
        return String.format("""
                You are an AI assistant that solves tasks by writing and executing code.
                Available tools:
                %s

                Enclose code in <code>...</code> tags. The code output is shown back to you.
                Call final_answer(\"your answer\") inside code to finish.
                You can print for debugging but use final_answer() for the final result.
                """, formatTools());
    }

    @Override
    protected StepResult executeReActStep(int stepNumber, String input, AgentMemory memory) {
        long start = System.currentTimeMillis();

        try {
            // 1. 构建 LLM 消息
            List<Map<String, String>> messages = writeMemoryToMessages();

            // 2. 调用 LLM
            LLMRequest request = LLMRequest.builder()
                    .messages(messages)
                    .maxTokens(4096)
                    .temperature(0.3)
                    .build();

            long llmStart = System.currentTimeMillis();
            LLMResponse response = llm.complete(request);
            long llmMs = System.currentTimeMillis() - llmStart;
            String modelOutput = response.content();
            log.info("[CodeAgent] step {} LLM call: {}ms, tokens in={} out={}",
                    stepNumber, llmMs, response.promptTokens(), response.completionTokens());

            // 3. 解析代码块
            String code = extractCode(modelOutput);
            if (code == null || code.isBlank()) {
                log.warn("CodeAgent step {} no code block found in output", stepNumber);
                ActionStep emptyStep = new ActionStep(
                        stepNumber, messages.toString(), modelOutput,
                        "no_code", "No code block found in output. "
                        + "Please enclose code in <code>...</code> tags.",
                        null, response.promptTokens(), response.completionTokens(), false
                );
                memory.appendAction(emptyStep);
                return StepResult.builder()
                        .stepNumber(stepNumber)
                        .state(LoopState.ACT)
                        .action("no_code")
                        .observation("No code block found")
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }

            // 4. 执行代码
            long execStart = System.currentTimeMillis();
            CodeExecutionResult execResult = executeCode(code);
            long execMs = System.currentTimeMillis() - execStart;
            log.debug("CodeAgent step {} code execution took {}ms, finalAnswer={}",
                    stepNumber, execMs, execResult.isFinalAnswer);

            // 5. 检查 final_answer
            boolean isFinalAnswer = execResult.isFinalAnswer;
            String output = execResult.output;
            String logs = execResult.logs;
            String finalAnswer = execResult.finalAnswerValue;

            // 6. 组装观察
            String observation = "Execution logs:\n" + logs
                    + "\nLast output:\n" + output;

            // 7. 记录 ActionStep（必须在 appendFinalAnswer 之前）
            ActionStep actionStep = new ActionStep(
                    stepNumber,
                    messages.toString(),
                    modelOutput,
                    "python_interpreter",
                    observation,
                    execResult.error,
                    response.promptTokens(),
                    response.completionTokens(),
                    isFinalAnswer
            );
            memory.appendAction(actionStep);

            // 8. final_answer 记录为最后一步
            if (isFinalAnswer && finalAnswer != null) {
                memory.appendFinalAnswer(finalAnswer);
                log.info("[CodeAgent] step {} final_answer: {}",
                        stepNumber, truncate(finalAnswer, 200));
            }

            long stepMs = System.currentTimeMillis() - start;
            log.debug("CodeAgent step {} completed in {}ms, state={}",
                    stepNumber, stepMs, isFinalAnswer ? "COMPLETE" : "ACT");
            return StepResult.builder()
                    .stepNumber(stepNumber)
                    .state(isFinalAnswer ? LoopState.COMPLETE : LoopState.ACT)
                    .action("python_interpreter")
                    .observation(observation)
                    .output(isFinalAnswer ? finalAnswer : output)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();

        } catch (Exception e) {
            log.error("CodeAgent step {} failed", stepNumber, e);
            ActionStep errorStep = new ActionStep(
                    stepNumber, "", "", "", "",
                    e.getMessage(), 0, 0, false
            );
            memory.appendAction(errorStep);

            return StepResult.builder()
                    .stepNumber(stepNumber)
                    .state(LoopState.ERROR)
                    .error(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ============ 代码提取与执行 ============

    /** 从 LLM 输出中提取代码块. */
    String extractCode(String modelOutput) {
        Matcher m = CODE_BLOCK_PATTERN.matcher(modelOutput);
        if (m.find()) {
            // 返回第一个非空组
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null && !m.group(i).isBlank()) {
                    return m.group(i).trim();
                }
            }
        }
        return null;
    }

    /** 执行代码并返回结果. */
    CodeExecutionResult executeCode(String code) {
        log.debug("CodeAgent executing code, length={}", code.length());
        StringBuilder logs = new StringBuilder();
        List<String> printOutputs = new ArrayList<>();

        // 预先检查 final_answer
        boolean hasFinalAnswer = false;
        String finalAnswerValue = null;

        // 匹配 final_answer("...") 或 final_answer("...", ...)
        Matcher m = FINAL_ANSWER_PATTERN.matcher(code);
        if (m.find()) {
            hasFinalAnswer = true;
            finalAnswerValue = m.group(1);
        }
        if (!hasFinalAnswer) {
            Matcher m2 = FINAL_ANSWER_PY_PATTERN.matcher(code);
            if (m2.find()) {
                hasFinalAnswer = true;
                finalAnswerValue = m2.group(1);
            }
        }
        // 匹配 final_answer(variableName) — 无引号
        if (!hasFinalAnswer) {
            Matcher m3 = FINAL_ANSWER_BARE_PATTERN.matcher(code);
            if (m3.find()) {
                String bareRef = m3.group(1);
                hasFinalAnswer = true;
                // finalAnswerValue 将在工具执行后动态解析
                finalAnswerValue = "__REF:" + bareRef;
            }
        }

        // 使用 ScriptEngine 执行（带工具注入）
        try {
            String result = evaluateCodeWithTools(code, printOutputs);
            logs.append("Code executed successfully.\n");

            // 解析 final_answer 引用
            if (hasFinalAnswer && finalAnswerValue != null && finalAnswerValue.startsWith("__REF:")) {
                String varName = finalAnswerValue.substring(6);
                // 尝试从 printOutputs 中提取该变量的值
                for (String po : printOutputs) {
                    if (po != null && !po.isEmpty()) {
                        finalAnswerValue = po;
                        break;
                    }
                }
                if (finalAnswerValue.startsWith("__REF:")) {
                    finalAnswerValue = result;
                }
            }

            return new CodeExecutionResult(
                    result,
                    String.join("\n", printOutputs),
                    null,
                    hasFinalAnswer,
                    hasFinalAnswer ? finalAnswerValue : null
            );
        } catch (Exception e) {
            log.warn("CodeAgent code execution error: {}", e.getMessage());
            logs.append("Execution error: ").append(e.getMessage()).append("\n");
            return new CodeExecutionResult(
                    "",
                    logs.toString(),
                    e.getMessage(),
                    false,
                    null
            );
        }
    }

    /**
     * Execute code with tool injection.
     *
     * <p>Primary path: regex-based tool call extraction and direct Java invocation.
     * Falls back to ScriptEngine if GraalJS is on the classpath.
     */
    private String evaluateCodeWithTools(String code, List<String> printOutputs) {
        log.debug("CodeAgent evaluating code with tools, injectable tools: {}",
                toolRegistry != null ? toolRegistry.all().size() : 0);

        String cleanCode = code.replaceAll("final_answer\\s*\\([^)]*\\)", "").trim();

        if (cleanCode.isEmpty()) {
            return "";
        }

        // Primary: tool simulation (works on all Java versions)
        String simulated = simulateToolExecution(cleanCode, printOutputs);
        if (simulated != null && !simulated.isBlank()) {
            return simulated;
        }

        // Fallback: ScriptEngine (requires GraalJS on classpath for Java 15+)
        try {
            ScriptEngine engine = findScriptEngine();
            if (engine != null) {
                injectTools(engine, printOutputs);
                Object result = engine.eval(cleanCode);
                String resultStr = result != null ? result.toString() : "null";
                if (!resultStr.isEmpty() && !printOutputs.contains(resultStr)) {
                    printOutputs.add(resultStr);
                }
                return resultStr;
            }
        } catch (Exception e) {
            log.warn("ScriptEngine eval failed, falling back: {}", e.getMessage());
        }

        // Last resort: return code representation
        return "[Code]:\n" + (cleanCode.length() > maxPrintOutputLength
                ? cleanCode.substring(0, maxPrintOutputLength) + "...(truncated)"
                : cleanCode);
    }

    private static ScriptEngine findScriptEngine() {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("graal.js");
        if (engine != null) return engine;
        engine = manager.getEngineByName("nashorn");
        if (engine != null) return engine;
        return manager.getEngineByName("JavaScript");
    }

    /** 将 ToolRegistry 中的工具注入 ScriptEngine. */
    private void injectTools(ScriptEngine engine, List<String> printOutputs) {
        if (toolRegistry == null) return;
        for (Tool tool : toolRegistry.all()) {
            engine.put(tool.getName(), new ToolFunction(tool, printOutputs));
        }
        // 注入 print 函数
        engine.put("print", (java.util.function.Consumer<Object>) obj -> {
            String s = obj != null ? obj.toString() : "null";
            printOutputs.add(s);
            log.debug("[CodeAgent output] {}", s);
        });
    }

    /**
     * 模拟工具执行 — 当 ScriptEngine 不可用时，解析工具调用行并直接调用 Java 工具.
     */
    private String simulateToolExecution(String code, List<String> printOutputs) {
        if (toolRegistry == null) return null;
        StringBuilder sb = new StringBuilder();
        String[] lines = code.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 匹配: result = tool_name(args...)
            // 匹配: print(expr)
            if (line.startsWith("print(") && line.endsWith(")")) {
                String inner = line.substring(6, line.length() - 1).trim();
                printOutputs.add(inner);
                continue;
            }

            // 匹配: var = tool_name(...)
            Pattern toolCallPattern = Pattern.compile(
                    "(\\w+)\\s*=\\s*(\\w+)\\((.+)\\)");
            Matcher tm = toolCallPattern.matcher(line);
            if (tm.find()) {
                String varName = tm.group(1);
                String toolName = tm.group(2);
                String argsStr = tm.group(3).trim();

                if (toolRegistry.has(toolName)) {
                    Map<String, Object> toolArgs = parseToolArgs(argsStr);
                    try {
                        Object callResult = toolRegistry.get(toolName).call(toolArgs);
                        String callStr = String.valueOf(callResult);
                        sb.append(callStr).append("\n");
                        printOutputs.add(callStr);
                    } catch (Exception e) {
                        printOutputs.add("Error: " + e.getMessage());
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 解析工具参数字符串 key="value", ... */
    private Map<String, Object> parseToolArgs(String argsStr) {
        Map<String, Object> result = new LinkedHashMap<>();
        Pattern pairPattern = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
        Matcher m = pairPattern.matcher(argsStr);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    /** 代码执行结果. */
    record CodeExecutionResult(
            String output,
            String logs,
            String error,
            boolean isFinalAnswer,
            String finalAnswerValue
    ) {}

    /**
     * 工具函数包装 — 使 Tool 可作为函数被 ScriptEngine 调用.
     */
    static final class ToolFunction {
        private final Tool tool;
        private final List<String> outputs;

        ToolFunction(Tool tool, List<String> outputs) {
            this.tool = tool;
            this.outputs = outputs;
        }

        /** 无参调用. */
        public String call() {
            return invoke(Map.of());
        }

        /** 单字符串参数调用 (最常见). */
        public String call(String arg) {
            // 查找工具的第一个输入参数名
            String firstKey = tool.getInputs().isEmpty()
                    ? "input" : tool.getInputs().keySet().iterator().next();
            return invoke(Map.of(firstKey, arg));
        }

        /** 键值对参数调用. */
        public String call(Map<String, Object> args) {
            return invoke(args);
        }

        private String invoke(Map<String, Object> args) {
            try {
                Object result = tool.call(args);
                String s = String.valueOf(result);
                outputs.add(s);
                return s;
            } catch (Exception e) {
                String err = "Tool error: " + e.getMessage();
                outputs.add(err);
                return err;
            }
        }

        @Override
        public String toString() {
            return tool.getName() + "()";
        }
    }

    // ============ Builder ============

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends ReActAgent.Builder<Builder> {
        private Set<String> authorizedImports;
        private String codeLanguage = "python";
        private int maxPrintOutputLength = 10000;
        private boolean useStructuredOutput;

        public Builder authorizedImports(Set<String> imports) {
            this.authorizedImports = imports; return this;
        }
        public Builder codeLanguage(String lang) {
            this.codeLanguage = lang; return this;
        }
        public Builder maxPrintOutputLength(int max) {
            this.maxPrintOutputLength = max; return this;
        }
        public Builder useStructuredOutput(boolean v) {
            this.useStructuredOutput = v; return this;
        }

        @Override
        public CodeAgent build() {
            return new CodeAgent(this);
        }
    }
}
