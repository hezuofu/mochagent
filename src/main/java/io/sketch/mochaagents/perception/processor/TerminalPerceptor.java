package io.sketch.mochaagents.perception.processor;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 终端感知器 — 执行命令，捕获 stdout/stderr，感知执行环境状态.
 * @author lanxia39@163.com
 */
public class TerminalPerceptor implements Perceptor<String, Observation> {

    private static final Logger log = LoggerFactory.getLogger(TerminalPerceptor.class);
    private static final int MAX_OUTPUT = 10000;
    private static final long TIMEOUT_SEC = 30;

    @Override
    public PerceptionResult<Observation> perceive(String command) {
        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > MAX_OUTPUT) { output.append("...(truncated)"); break; }
                }
            }

            boolean finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) { proc.destroyForcibly(); output.append("\n[Terminated after timeout]"); }

            String result = String.format("Command: %s (exit=%d)\n%s",
                    command, finished ? proc.exitValue() : -1, output.toString());
            log.debug("TerminalPerceptor executed: {}", command);
            return PerceptionResult.of(
                    new Observation("terminal", result, "terminal"), "terminal");
        } catch (Exception e) {
            log.warn("TerminalPerceptor failed: {}", e.getMessage());
            return PerceptionResult.of(
                    new Observation("terminal", "Error: " + e.getMessage(), "terminal"), "terminal");
        }
    }

    @Override
    public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
        return CompletableFuture.supplyAsync(() -> perceive(input));
    }
}
