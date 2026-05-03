package io.sketch.mochaagents.llm;

/**
 * API error classification and retry predicates — pattern from claude-code's errors.ts.
 * @author lanxia39@163.com
 */
public final class ApiErrors {
    private ApiErrors() {}

    public record ApiError(int statusCode, String message, boolean isRetryable, boolean isRateLimit, boolean isPromptTooLong) {}

    /** Classify an HTTP error for retry decisions. */
    public static ApiError classify(int statusCode, String body) {
        boolean rateLimit = statusCode == 429 || statusCode == 529;
        boolean retryable = rateLimit || statusCode >= 500;
        boolean ptl = body != null && body.contains("prompt too long");
        return new ApiError(statusCode, body, retryable, rateLimit, ptl);
    }

    /** Maximum retry attempts for transient failures. */
    public static final int MAX_RETRIES = 3;
    /** Base backoff in milliseconds. */
    public static final long RETRY_BASE_MS = 1000;
    /** Backoff multiplier. */
    public static final double RETRY_BACKOFF = 2.0;

    /** Calculate exponential backoff delay. */
    public static long backoffMs(int attempt) { return (long) (RETRY_BASE_MS * Math.pow(RETRY_BACKOFF, attempt)); }
}
