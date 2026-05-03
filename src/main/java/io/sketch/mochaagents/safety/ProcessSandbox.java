package io.sketch.mochaagents.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * Process-level sandbox — executes code/commands in a separate OS process
 * with timeout, output capture, and resource limits.
 * @author lanxia39@163.com
 */
public class ProcessSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(ProcessSandbox.class);

    private final long timeoutMs;
    private final int maxOutputChars;
    private final boolean networkDisabled;

    public ProcessSandbox(long timeoutMs, int maxOutputChars, boolean networkDisabled) {
        this.timeoutMs = timeoutMs;
        this.maxOutputChars = maxOutputChars;
        this.networkDisabled = networkDisabled;
    }

    public ProcessSandbox() { this(30_000, 50_000, true); }

    @Override
    public String execute(String code, String language) {
        try {
            ProcessBuilder pb = buildProcess(code, language);
            Process proc = pb.start();

            // Capture stdout/stderr in parallel
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread outThread = readStream(proc.getInputStream(), out);
            Thread errThread = readStream(proc.getErrorStream(), err);
            outThread.start(); errThread.start();

            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            outThread.join(5000); errThread.join(5000);

            if (!finished) {
                proc.destroyForcibly();
                return "[ProcessSandbox] Execution timed out after " + timeoutMs + "ms";
            }

            int exitCode = proc.exitValue();
            String output = out.toString();
            if (output.length() > maxOutputChars)
                output = output.substring(0, maxOutputChars) + "...(truncated)";

            return exitCode == 0 ? output
                    : "[ProcessSandbox] Exit code " + exitCode + "\n" + output
                    + (err.length() > 0 ? "\n[stderr]\n" + err : "");
        } catch (Exception e) {
            log.error("ProcessSandbox execution failed: {}", e.getMessage());
            return "[ProcessSandbox] Error: " + e.getMessage();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(java.util.function.Supplier<T> operation) {
        // Process sandbox: run the supplier in current JVM but with timeout via CompletableFuture
        try {
            var future = java.util.concurrent.CompletableFuture.supplyAsync(operation);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Sandbox operation timed out after " + timeoutMs + "ms", e);
        } catch (Exception e) {
            throw new RuntimeException("Sandbox operation failed: " + e.getMessage(), e);
        }
    }

    private ProcessBuilder buildProcess(String code, String language) {
        String os = System.getProperty("os.name").toLowerCase();
        if ("python".equalsIgnoreCase(language) || "py".equalsIgnoreCase(language)) {
            return os.contains("win")
                    ? new ProcessBuilder("python", "-c", code)
                    : new ProcessBuilder("python3", "-c", code);
        }
        if ("javascript".equalsIgnoreCase(language) || "js".equalsIgnoreCase(language)) {
            return os.contains("win")
                    ? new ProcessBuilder("node", "-e", code)
                    : new ProcessBuilder("node", "-e", code);
        }
        // Default: shell execution
        return os.contains("win")
                ? new ProcessBuilder("cmd.exe", "/c", code)
                : new ProcessBuilder("sh", "-c", code);
    }

    private Thread readStream(InputStream stream, StringBuilder target) {
        return new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = r.readLine()) != null && target.length() < maxOutputChars)
                    target.append(line).append('\n');
            } catch (IOException ignored) {}
        });
    }
}
