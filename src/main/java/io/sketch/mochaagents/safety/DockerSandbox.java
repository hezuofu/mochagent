package io.sketch.mochaagents.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * Docker-based sandbox — runs tools/code in isolated containers with resource limits.
 * Pattern: docker run --rm --network=none --memory=512m --cpus=1 alpine sh -c '...'
 * @author lanxia39@163.com
 */
public class DockerSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private final String image;
    private final long timeoutMs;
    private final int maxOutputChars;
    private final String memoryLimit;
    private final String cpuLimit;

    public DockerSandbox(String image, long timeoutMs, int maxOutputChars,
                          String memoryLimit, String cpuLimit) {
        this.image = image;
        this.timeoutMs = timeoutMs;
        this.maxOutputChars = maxOutputChars;
        this.memoryLimit = memoryLimit;
        this.cpuLimit = cpuLimit;
    }

    public DockerSandbox() { this("alpine:latest", 30_000, 50_000, "512m", "1"); }

    @Override
    public String execute(String code, String language) {
        String shellCmd = switch (language.toLowerCase()) {
            case "python", "py" -> "python3 -c " + shellEscape(code);
            case "javascript", "js" -> "node -e " + shellEscape(code);
            case "shell", "sh" -> code;
            default -> "echo 'Unsupported: " + language + "'";
        };

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "--rm",
                    "--network=" + (networkDisabled() ? "none" : "bridge"),
                    "--memory=" + memoryLimit,
                    "--cpus=" + cpuLimit,
                    image, "sh", "-c", shellCmd);
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null && output.length() < maxOutputChars)
                    output.append(line).append('\n');
            }

            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) { proc.destroyForcibly(); output.append("\n[Timeout]"); }

            String result = output.toString();
            if (result.length() > maxOutputChars) result = result.substring(0, maxOutputChars) + "...";
            log.debug("DockerSandbox: exit={}, output={} chars", finished ? proc.exitValue() : -1, result.length());
            return result;
        } catch (Exception e) {
            log.error("DockerSandbox failed: {}", e.getMessage());
            return "[DockerSandbox error: " + e.getMessage() + "]";
        }
    }

    @Override
    public String backendName() { return "DockerSandbox(" + image + ")"; }

    private boolean networkDisabled() { return true; }

    private static String shellEscape(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
