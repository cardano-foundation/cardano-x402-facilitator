package org.cardanofoundation.x402.facilitator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional edge protection for the mutating endpoints: an API key allowlist
 * and a per-key fixed-window rate limit. Both are off unless
 * configured, so the default posture is unchanged. Guards {@code /verify} and
 * {@code /settle} only — {@code /supported}, {@code /health} and actuator stay
 * open for discovery and orchestration probes. Runs after the correlation filter
 * so rejections are still traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiGuardFilter extends OncePerRequestFilter {

    private static final Set<String> GUARDED = Set.of("/verify", "/settle");

    private final Set<String> apiKeys;
    private final int requestsPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweptMinute = new AtomicLong(Long.MIN_VALUE);

    public ApiGuardFilter(X402Properties props, Clock facilitatorClock) {
        X402Properties.Security security = props.security();
        this.apiKeys = security == null ? Set.of() : Set.copyOf(security.apiKeysOrDefault());
        this.requestsPerMinute = security == null ? 0 : security.requestsPerMinuteOrDefault();
        this.clock = facilitatorClock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!GUARDED.contains(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        String apiKey = request.getHeader("X-API-Key");
        if (!apiKeys.isEmpty() && (apiKey == null || !apiKeys.contains(apiKey))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid or missing API key");
            return;
        }
        if (requestsPerMinute > 0) {
            String bucket = apiKey != null ? "k:" + apiKey : "ip:" + request.getRemoteAddr();
            if (overLimit(bucket)) {
                response.setHeader("Retry-After", "60");
                response.sendError(429, "rate limit exceeded");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Fixed-window counter: at most {@code requestsPerMinute} per bucket per wall-clock minute. */
    private boolean overLimit(String bucket) {
        long minute = clock.millis() / 60_000L;
        evictStaleWindows(minute);
        Window w = windows.compute(bucket, (k, existing) -> {
            if (existing == null || existing.minute != minute) return new Window(minute);
            return existing;
        });
        return w.count.incrementAndGet() > requestsPerMinute;
    }

    /**
     * Bound map growth: once per new wall-clock minute, drop windows from prior
     * minutes. Without this, distinct client IPs (or an IP-rotation abuse pattern)
     * would accumulate {@link Window} entries for the life of the process. The CAS
     * lets exactly one thread sweep per minute transition; entries created for the
     * current minute are never removed (predicate is strictly {@code < currentMinute}).
     */
    private void evictStaleWindows(long currentMinute) {
        long prev = lastSweptMinute.get();
        if (currentMinute > prev && lastSweptMinute.compareAndSet(prev, currentMinute)) {
            windows.values().removeIf(w -> w.minute < currentMinute);
        }
    }

    /** Number of live rate-limit buckets; for observability and eviction tests. */
    int trackedBucketCount() {
        return windows.size();
    }

    private static final class Window {
        final long minute;
        final AtomicLong count = new AtomicLong();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
