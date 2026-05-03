package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Web 页面抓取工具 — 对齐 claude-code 的 WebFetchTool.
 *
 * <p>获取 URL 内容并转换为 Markdown，支持 prompt 引导的内容提取。
 * 处理重定向和错误状态码。
 * @author lanxia39@163.com
 */
public class WebFetchTool extends AbstractTool {

    private static final String NAME = "web_fetch";
    private static final int MAX_CONTENT_LENGTH = 500_000;
    private static final Pattern HTML_TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_BODY = Pattern.compile("<body[^>]*>(.*?)</body>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final HttpClient httpClient;

    public WebFetchTool() {
        super(builder(NAME, "Fetch content from a URL and process it. "
                        + "Supports HTML-to-Markdown conversion and prompt-based extraction.",
                SecurityLevel.LOW)
                .readOnly(true)
                .concurrencySafe(true)
                .searchHint("fetch and extract content from a URL")
        );
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("url", "prompt")
                .inputProperty("url", "string", "The URL to fetch content from", true)
                .inputProperty("prompt", "string", "The prompt to run on the fetched content", true)
                .outputType("object")
                .outputProperty("url", "string", "The URL that was fetched")
                .outputProperty("code", "integer", "HTTP response code")
                .outputProperty("codeText", "string", "HTTP response code text")
                .outputProperty("result", "string", "Processed result from content extraction")
                .outputProperty("bytes", "integer", "Size of the fetched content in bytes")
                .outputProperty("durationMs", "integer", "Time taken to fetch and process")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("url", ToolInput.string("The URL to fetch content from"));
        inputs.put("prompt", ToolInput.string("The prompt to run on the fetched content"));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String url = (String) arguments.get("url");
        if (url == null || url.isBlank()) {
            return ValidationResult.invalid("url is required", 1);
        }
        try {
            new URI(url).toURL();
        } catch (Exception e) {
            return ValidationResult.invalid("Invalid URL: " + url, 2);
        }
        return ValidationResult.valid();
    }

    @Override
    public Object call(Map<String, Object> arguments) {
        long start = System.currentTimeMillis();
        String url = (String) arguments.get("url");
        String prompt = (String) arguments.get("prompt");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "MochaAgents/1.0 (WebFetch)")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            int code = response.statusCode();
            String codeText = getStatusText(code);
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("text/html");
            String body = response.body();

            // Handle redirects (3xx)
            if (code >= 300 && code < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                String message = "REDIRECT DETECTED: The URL redirects to a different host.\n\n"
                        + "Original URL: " + url + "\n"
                        + "Redirect URL: " + location + "\n"
                        + "Status: " + code + " " + codeText + "\n\n"
                        + "To complete, use WebFetch with url: \"" + location + "\"";

                Map<String, Object> redirectResult = new LinkedHashMap<>();
                redirectResult.put("url", url);
                redirectResult.put("code", code);
                redirectResult.put("codeText", codeText);
                redirectResult.put("result", message);
                redirectResult.put("bytes", body.length());
                redirectResult.put("durationMs", System.currentTimeMillis() - start);
                return redirectResult;
            }

            // Error status codes
            if (code >= 400) {
                String message = "HTTP " + code + " " + codeText + " when fetching " + url;
                Map<String, Object> errorResult = new LinkedHashMap<>();
                errorResult.put("url", url);
                errorResult.put("code", code);
                errorResult.put("codeText", codeText);
                errorResult.put("result", message);
                errorResult.put("bytes", 0);
                errorResult.put("durationMs", System.currentTimeMillis() - start);
                return errorResult;
            }

            // Extract content based on content type
            String result;
            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                result = extractFromHtml(body, prompt);
            } else if (contentType.contains("text/plain") || contentType.contains("text/markdown")) {
                result = extractFromText(body, prompt);
            } else if (contentType.contains("application/json")) {
                result = body; // Return raw JSON
                if (result.length() > MAX_CONTENT_LENGTH) {
                    result = result.substring(0, MAX_CONTENT_LENGTH) + "\n... [truncated]";
                }
            } else {
                result = "[Binary content: " + contentType + ", " + body.length() + " bytes]";
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("url", url);
            output.put("code", code);
            output.put("codeText", codeText);
            output.put("result", result);
            output.put("bytes", body.length());
            output.put("durationMs", System.currentTimeMillis() - start);
            return output;

        } catch (IOException | InterruptedException e) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("url", url);
            errorResult.put("code", 0);
            errorResult.put("codeText", "Connection Error");
            errorResult.put("result", "Failed to fetch URL: " + e.getMessage());
            errorResult.put("bytes", 0);
            errorResult.put("durationMs", System.currentTimeMillis() - start);
            return errorResult;
        }
    }

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        return (String) map.getOrDefault("result", "");
    }

    // ==================== Content Extraction ====================

    private String extractFromHtml(String html, String prompt) {
        // Extract title
        String title = "";
        java.util.regex.Matcher titleMatcher = HTML_TITLE.matcher(html);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).trim();
        }

        // Strip scripts and styles
        String cleaned = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", "");

        // Extract body content
        String bodyContent = cleaned;
        java.util.regex.Matcher bodyMatcher = HTML_BODY.matcher(cleaned);
        if (bodyMatcher.find()) {
            bodyContent = bodyMatcher.group(1);
        }

        // Convert to plain text
        String text = htmlToText(bodyContent);

        // Truncate if too large
        if (text.length() > MAX_CONTENT_LENGTH) {
            text = text.substring(0, MAX_CONTENT_LENGTH) + "\n... [content truncated]";
        }

        // Build result with prompt context
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) sb.append("# ").append(title).append("\n\n");
        sb.append("## Prompt: ").append(prompt).append("\n\n");
        sb.append("## Content:\n").append(text);

        return sb.toString();
    }

    private String extractFromText(String text, String prompt) {
        if (text.length() > MAX_CONTENT_LENGTH) {
            text = text.substring(0, MAX_CONTENT_LENGTH) + "\n... [content truncated]";
        }
        return "## Prompt: " + prompt + "\n\n## Content:\n" + text;
    }

    private static String htmlToText(String html) {
        // Simple HTML to text conversion
        return html
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)<p[^>]*>", "\n\n")
                .replaceAll("(?is)</p>", "")
                .replaceAll("(?is)<li[^>]*>", "\n- ")
                .replaceAll("(?is)</li>", "")
                .replaceAll("(?is)<h[1-6][^>]*>", "\n\n**")
                .replaceAll("(?is)</h[1-6]>", "**\n")
                .replaceAll("(?is)<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>([^<]*)</a>", "[$2]($1)")
                .replaceAll("(?is)<strong[^>]*>(.*?)</strong>", "**$1**")
                .replaceAll("(?is)<em[^>]*>(.*?)</em>", "*$1*")
                .replaceAll("(?is)<code[^>]*>(.*?)</code>", "`$1`")
                .replaceAll("(?is)<pre[^>]*>(.*?)</pre>", "\n```\n$1\n```\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static String getStatusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }
}
