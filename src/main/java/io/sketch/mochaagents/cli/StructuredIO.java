package io.sketch.mochaagents.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Structured IO layer — provides NDJSON-based message passing over stdin/stdout,
 * implementing the SDK control protocol (control_request / control_response).
 *
 * <p>Aligns with {@code claude-code/src/cli/structuredIO.ts class StructuredIO}:
 * <ul>
 *   <li>NDJSON line-delimited message parsing from stdin</li>
 *   <li>Request/response correlation via {@code pendingRequests} map</li>
 *   <li>Permission request forwarding ({@code can_use_tool})</li>
 *   <li>Hook callback forwarding</li>
 *   <li>Elicitation request forwarding</li>
 *   <li>MCP message forwarding</li>
 *   <li>Duplicate control_response detection via resolvedToolUseIds Set</li>
 * </ul>
 *
 * <h3>Message Types</h3>
 * Messages flowing over stdin/stdout use NDJSON framing: one JSON object per
 * line, terminated by {@code \n}. The {@code type} field discriminates:
 * <ul>
 *   <li>{@code user} — user messages (role: user)</li>
 *   <li>{@code assistant} — assistant response messages</li>
 *   <li>{@code control_request} — SDK host requests permission/callback</li>
 *   <li>{@code control_response} — SDK consumer response to a request</li>
 *   <li>{@code control_cancel_request} — cancel an outstanding request</li>
 *   <li>{@code keep_alive} — silently ignored heartbeat</li>
 * </ul>
 * @author lanxia39@163.com
 */
public final class StructuredIO implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StructuredIO.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * Maximum number of resolved tool_use IDs to track. Once exceeded, the
     * oldest entry is evicted (LRU via LinkedHashSet insertion order).
     * Aligns with MAX_RESOLVED_TOOL_USE_IDS = 1000.
     */
    private static final int MAX_RESOLVED_TOOL_USE_IDS = 1000;

    private final BufferedReader stdin;
    private final PrintWriter stdout;
    private final boolean replayUserMessages;

    private final ConcurrentMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Set<String> resolvedToolUseIds = Collections.synchronizedSet(new LinkedHashSet<>());

    private final BlockingQueue<Map<String, Object>> outbound = new LinkedBlockingQueue<>();
    private final List<String> prependedLines = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean inputClosed = false;
    private volatile boolean running = false;
    private Consumer<Map<String, Object>> unexpectedResponseCallback;
    private Consumer<Map<String, Object>> onControlRequestSent;
    private Consumer<String> onControlRequestResolved;

    /**
     * Create a StructuredIO instance reading from stdin and writing to stdout.
     *
     * @param replayUserMessages if true, propagate control_responses as messages
     */
    public StructuredIO(boolean replayUserMessages) {
        this.stdin = new BufferedReader(new InputStreamReader(System.in));
        this.stdout = new PrintWriter(System.out, true);
        this.replayUserMessages = replayUserMessages;
        log.debug("StructuredIO created, replayUserMessages={}", replayUserMessages);
    }

    /**
     * Create a StructuredIO instance with custom streams (for testing).
     */
    StructuredIO(BufferedReader in, PrintWriter out, boolean replayUserMessages) {
        this.stdin = in;
        this.stdout = out;
        this.replayUserMessages = replayUserMessages;
        log.debug("StructuredIO created with custom streams, replayUserMessages={}", replayUserMessages);
    }

    // ─── Callback Registration ───

    /** Register callback for unexpected (orphan) control_response messages. */
    public void setUnexpectedResponseCallback(Consumer<Map<String, Object>> callback) {
        this.unexpectedResponseCallback = callback;
        log.debug("Unexpected response callback registered");
    }

    /** Register callback invoked when a can_use_tool control_request is sent. */
    public void setOnControlRequestSent(Consumer<Map<String, Object>> callback) {
        this.onControlRequestSent = callback;
        log.debug("Control-request-sent callback registered");
    }

    /** Register callback invoked when a can_use_tool control_response arrives. */
    public void setOnControlRequestResolved(Consumer<String> callback) {
        this.onControlRequestResolved = callback;
        log.debug("Control-request-resolved callback registered");
    }

    /** Returns pending can_use_tool permission requests. */
    public List<Map<String, Object>> getPendingPermissionRequests() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List) pendingRequests.values().stream()
                .map(pr -> pr.request)
                .filter(req -> "can_use_tool".equals(
                        nestedString(req, "request", "subtype")))
                .toList();
        return result;
    }

    // ─── Message Queueing ───

    /**
     * Queue a user message to be yielded before the next message from stdin.
     * Works before iteration starts and mid-stream.
     */
    public void prependUserMessage(String content) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "user");
            msg.put("session_id", "");
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("role", "user");
            inner.put("content", content);
            msg.put("message", inner);
            msg.put("parent_tool_use_id", null);
            String json = MAPPER.writeValueAsString(msg);
            prependedLines.add(json);
            log.debug("User message prepended: {}", abbreviate(content, 60));
        } catch (IOException e) {
            log.error("Failed to serialize prepended user message", e);
            throw new UncheckedIOException(e);
        }
    }

    // ─── Reading ───

    /**
     * Read the next message from stdin. Returns null when the stream is closed.
     *
     * <p>Messages are parsed as NDJSON: one JSON object per line.
     * Messages with type {@code keep_alive} are silently skipped.
     */
    public Map<String, Object> readMessage() throws IOException {
        while (running || !inputClosed) {
            // Check prepended lines first
            String line;
            synchronized (prependedLines) {
                if (!prependedLines.isEmpty()) {
                    line = prependedLines.remove(0);
                } else {
                    line = stdin.readLine();
                    if (line == null) {
                        inputClosed = true;
                        return null;
                    }
                }
            }

            if (line.isEmpty()) {
                continue; // skip empty lines
            }

            Map<String, Object> message = parseLine(line);
            if (message == null) {
                continue; // parsing error or keep_alive
            }

            String type = (String) message.get("type");

            // Silently ignore keep_alive
            if ("keep_alive".equals(type)) {
                continue;
            }

            // Handle control_response
            if ("control_response".equals(type)) {
                handleControlResponse(message);
                // Only propagate if replay mode is on
                if (replayUserMessages) {
                    log.debug("Replaying control_response message");
                    return message;
                }
                continue;
            }

            // Validate known message types
            if (!"user".equals(type) && !"assistant".equals(type)
                    && !"system".equals(type) && !"control_request".equals(type)) {
                log.warn("Ignoring unknown message type: {}", type);
                System.err.println("[StructuredIO] Ignoring unknown message type: " + type);
                continue;
            }

            log.debug("Read message: type={}", type);
        }
        return null;
    }

    // ─── Writing ───

    /**
     * Write an NDJSON message to stdout.
     * Equivalent to {@code writeToStdout(ndjsonSafeStringify(message) + '\n')}.
     */
    public void write(Map<String, Object> message) {
        try {
            String json = MAPPER.writeValueAsString(message);
            String msgType = (String) message.get("type");
            log.debug("Writing message: type={}, size={} chars", msgType, json.length());
            synchronized (stdout) {
                stdout.println(json);
                stdout.flush();
            }
        } catch (IOException e) {
            log.error("Failed to serialize outbound message", e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Enqueue a message for ordered output via the outbound queue.
     * Prevents control_request from overtaking queued stream_events.
     */
    public void enqueue(Map<String, Object> message) {
        outbound.offer(message);
        log.trace("Message enqueued, queue depth={}", outbound.size());
    }

    /**
     * Drain the outbound queue, writing all pending messages.
     */
    public void flushOutbound() {
        int count = 0;
        Map<String, Object> msg;
        while ((msg = outbound.poll()) != null) {
            write(msg);
            count++;
        }
        if (count > 0) {
            log.debug("Flushed {} outbound messages", count);
        }
    }

    // ─── Request/Response Protocol ───

    /**
     * Send a control_request and wait for the matching control_response.
     *
     * @param subtype  request subtype (can_use_tool, hook_callback, elicitation, mcp_message)
     * @param request  request payload
     * @param requestId unique request ID (random UUID)
     * @return the response payload
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws ExecutionException   if the request is rejected (stream closed, etc.)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendRequest(
            String subtype,
            Map<String, Object> request,
            String requestId) throws InterruptedException, ExecutionException {

        if (inputClosed) {
            log.warn("sendRequest blocked: stream closed, subtype={}", subtype);
            throw new ExecutionException(new IllegalStateException("Stream closed"));
        }

        log.debug("Sending control_request: id={}, subtype={}", requestId, subtype);

        Map<String, Object> controlRequest = new LinkedHashMap<>();
        controlRequest.put("type", "control_request");
        controlRequest.put("request_id", requestId);
        controlRequest.put("request", new LinkedHashMap<>(request));
        ((Map<String, Object>) controlRequest.get("request")).put("subtype", subtype);

        // Notify bridge callback
        if ("can_use_tool".equals(subtype) && onControlRequestSent != null) {
            onControlRequestSent.accept(controlRequest);
        }

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingRequests.put(requestId, new PendingRequest(controlRequest, future));
        
        // Write the request to stdout
        write(controlRequest);
        log.debug("Awaiting response for request id={}", requestId);

        try {
            return future.get();
        } catch (CancellationException e) {
            log.warn("Request cancelled: id={}", requestId);
            throw new InterruptedException("Request cancelled: " + requestId);
        }
    }

    /**
     * Cancel a pending request and write control_cancel_request to stdout.
     */
    public void cancelRequest(String requestId) {
        PendingRequest pr = pendingRequests.remove(requestId);
        if (pr != null) {
            log.info("Cancelling request: id={}", requestId);
            trackResolvedToolUseId(pr.request);
            pr.future.cancel(true);

            Map<String, Object> cancel = new LinkedHashMap<>();
            cancel.put("type", "control_cancel_request");
            cancel.put("request_id", requestId);
            write(cancel);
        }
    }

    // ─── Convenience Methods ───

    /**
     * Send a can_use_tool permission request.
     */
    public CompletableFuture<Map<String, Object>> requestPermission(
            String toolName,
            Map<String, Object> input,
            String toolUseId,
            List<Map<String, Object>> suggestions) {

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", toolName);
        payload.put("input", input);
        payload.put("tool_use_id", toolUseId);
        if (suggestions != null && !suggestions.isEmpty()) {
            payload.put("permission_suggestions", suggestions);
        }

        Map<String, Object> controlRequest = new LinkedHashMap<>();
        controlRequest.put("type", "control_request");
        controlRequest.put("request_id", requestId);
        Map<String, Object> inner = new LinkedHashMap<>(payload);
        inner.put("subtype", "can_use_tool");
        controlRequest.put("request", inner);

        if (onControlRequestSent != null) {
            onControlRequestSent.accept(controlRequest);
        }

        pendingRequests.put(requestId, new PendingRequest(controlRequest, future));
        write(controlRequest);

        return future;
    }

    /**
     * Send an MCP message to an SDK server and wait for the response.
     */
    public CompletableFuture<Map<String, Object>> sendMcpMessage(
            String serverName, Map<String, Object> message) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server_name", serverName);
        payload.put("message", message);

        Map<String, Object> controlRequest = new LinkedHashMap<>();
        controlRequest.put("type", "control_request");
        controlRequest.put("request_id", requestId);
        Map<String, Object> inner = new LinkedHashMap<>(payload);
        inner.put("subtype", "mcp_message");
        controlRequest.put("request", inner);

        pendingRequests.put(requestId, new PendingRequest(controlRequest, future));
        write(controlRequest);

        return future;
    }

    // ─── Lifecycle ───

    /** Start the read loop (non-blocking — call readMessage() in a loop). */
    public void start() {
        this.running = true;
        log.info("StructuredIO started");
    }

    /** Stop the read loop and reject all pending requests. */
    public void stop() {
        log.info("StructuredIO stopping, {} pending requests to cancel", pendingRequests.size());
        this.running = false;
        this.inputClosed = true;
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            entry.getValue().future.completeExceptionally(
                    new IllegalStateException("StructuredIO stopped"));
        }
        pendingRequests.clear();
        log.info("StructuredIO stopped");
    }

    @Override
    public void close() {
        stop();
    }

    // ─── Private Helpers ───

    /** Parse an NDJSON line into a Map. Returns null for empty/error lines. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLine(String line) {
        try {
            return MAPPER.readValue(line, MAP_TYPE);
        } catch (IOException e) {
            log.warn("Error parsing NDJSON line: {}: {}", abbreviate(line, 80), e.getMessage());
            System.err.println("[StructuredIO] Error parsing line: " + abbreviate(line, 80) + ": " + e.getMessage());
            return null;
        }
    }

    /** Handle an incoming control_response message. */
    @SuppressWarnings("unchecked")
    private void handleControlResponse(Map<String, Object> message) {
        Map<String, Object> response = (Map<String, Object>) message.get("response");
        if (response == null) {
            log.warn("control_response missing 'response' field");
            System.err.println("[StructuredIO] control_response missing 'response' field");
            return;
        }

        String requestId = (String) response.get("request_id");
        String subtype = (String) response.get("subtype");
        log.debug("Handling control_response: id={}, subtype={}", requestId, subtype);

        PendingRequest pr = pendingRequests.remove(requestId);
        if (pr == null) {
            // Orphan response — check if already resolved
            Map<String, Object> responsePayload = null;
            if ("success".equals(subtype)) {
                responsePayload = (Map<String, Object>) response.get("response");
            }
            String toolUseID = responsePayload != null
                    ? (String) responsePayload.get("toolUseID") : null;
            if (toolUseID != null && resolvedToolUseIds.contains(toolUseID)) {
                log.debug("Ignoring duplicate control_response for toolUseID={}", toolUseID);
                return;
            }
            log.warn("Orphan control_response: id={}, subtype={}", requestId, subtype);
            if (unexpectedResponseCallback != null) {
                unexpectedResponseCallback.accept(message);
            }
            return;
        }

        trackResolvedToolUseId(pr.request);

        // Notify bridge
        if (isCanUseToolRequest(pr.request) && onControlRequestResolved != null) {
            onControlRequestResolved.accept(requestId);
        }

        if ("error".equals(subtype)) {
            String errorMsg = (String) response.getOrDefault("error", "Unknown error");
            log.warn("control_response error: id={}, msg={}", requestId, errorMsg);
            pr.future.completeExceptionally(new RuntimeException(errorMsg));
            return;
        }

        log.debug("control_response resolved successfully: id={}", requestId);

        Object result = response.get("response");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = result instanceof Map
                ? (Map<String, Object>) result
                : Collections.emptyMap();
        pr.future.complete(resultMap);
    }

    private void trackResolvedToolUseId(Map<String, Object> request) {
        if (isCanUseToolRequest(request)) {
            Map<String, Object> inner = (Map<String, Object>) request.get("request");
            if (inner != null) {
                String toolUseId = (String) inner.get("tool_use_id");
                if (toolUseId != null) {
                    synchronized (resolvedToolUseIds) {
                        if (resolvedToolUseIds.size() >= MAX_RESOLVED_TOOL_USE_IDS) {
                            // Evict oldest (LinkedHashSet iterates in insertion order)
                            Iterator<String> it = resolvedToolUseIds.iterator();
                            if (it.hasNext()) {
                                String evicted = it.next();
                                it.remove();
                                log.trace("Evicted oldest resolved toolUseId: {}", evicted);
                            }
                        }
                        resolvedToolUseIds.add(toolUseId);
                        log.debug("Tracked resolved toolUseId: {} (total={})", toolUseId, resolvedToolUseIds.size());
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isCanUseToolRequest(Map<String, Object> request) {
        Map<String, Object> inner = (Map<String, Object>) request.get("request");
        return inner != null && "can_use_tool".equals(inner.get("subtype"));
    }

    @SuppressWarnings("unchecked")
    private static String nestedString(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }
        }
        return current instanceof String ? (String) current : null;
    }

    private static String abbreviate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // ─── Inner Types ───

    /** Internal pending request tracking. */
    @SuppressWarnings("rawtypes")
    private record PendingRequest(
            Map<String, Object> request,
            CompletableFuture future) {
    }
}
