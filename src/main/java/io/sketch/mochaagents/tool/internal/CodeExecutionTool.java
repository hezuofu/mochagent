// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.internal;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Execute Python/JavaScript/Shell code in a subprocess.
 *
 * <p>Called by ToolCallingAgent like any other tool:
 * <pre>Action: code(language="python", code="print(1+2)")</pre>
  * @author lanxia39@163.com
 */
public class CodeExecutionTool extends AbstractTool {

    private static final int DEFAULT_TIMEOUT_SEC = 30;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    public CodeExecutionTool() {
        super(builder("code", "Execute Python/JavaScript/Shell code in a subprocess. "
                        + "Use for math, data processing, or any logic too complex for a single tool call.",
                SecurityLevel.MEDIUM)
                .destructive(false)
                .searchHint("run python javascript shell code execute")
        );
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("language", "code")
                .inputProperty("language", "string", "python, javascript, or shell", true)
                .inputProperty("code", "string", "The code to execute", true)
                .inputProperty("timeout", "integer", "Timeout in seconds (default 30)", false)
                .outputType("object")
                .outputProperty("stdout", "string", "Standard output")
                .outputProperty("stderr", "string", "Standard error")
                .outputProperty("exitCode", "integer", "Process exit code")
                .build();
    }
    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String language = (String) arguments.get("language");
        String code = (String) arguments.get("code");
        if (language == null || language.isBlank())
            return ValidationResult.invalid("language is required", 1);
        if (code == null || code.isBlank())
            return ValidationResult.invalid("code is required", 2);
        return ValidationResult.valid();
    }

    @Override
    public Object call(Map<String, Object> arguments) {
        String language = (String) arguments.get("language");
        String code = (String) arguments.get("code");
        int timeout = getIntArg(arguments, "timeout", DEFAULT_TIMEOUT_SEC);

        ProcessBuilder pb = buildProcess(language, code);
        if (pb == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("stdout", "");
            err.put("stderr", "Unsupported language: " + language);
            err.put("exitCode", -1);
            return err;
        }

        try {
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();

            Thread outThread = readStream(proc.getInputStream(), out, MAX_OUTPUT_CHARS);
            Thread errThread = readStream(proc.getErrorStream(), err, MAX_OUTPUT_CHARS);
            outThread.start();
            errThread.start();

            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                outThread.join(1000);
                errThread.join(1000);
            } else {
                outThread.join(5000);
                errThread.join(5000);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stdout", out.toString());
            result.put("stderr", err.toString());
            result.put("exitCode", finished ? proc.exitValue() : -1);
            return result;

        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("stdout", "");
            err.put("stderr", e.getMessage());
            err.put("exitCode", -1);
            return err;
        }
    }

    private ProcessBuilder buildProcess(String language, String code) {
        return switch (language.toLowerCase()) {
            case "python", "py" -> new ProcessBuilder("python3", "-c", code);
            case "javascript", "js" -> new ProcessBuilder("node", "-e", code);
            case "shell", "sh", "bash" -> {
                boolean win = System.getProperty("os.name").toLowerCase().contains("win");
                yield win ? new ProcessBuilder("cmd.exe", "/c", code)
                          : new ProcessBuilder("sh", "-c", code);
            }
            default -> null;
        };
    }

    private Thread readStream(InputStream stream, StringBuilder target, int maxChars) {
        return new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = r.readLine()) != null && target.length() < maxChars)
                    target.append(line).append('\n');
            } catch (IOException ignored) {}
        });
    }

    private static int getIntArg(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isEmpty()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }
}
