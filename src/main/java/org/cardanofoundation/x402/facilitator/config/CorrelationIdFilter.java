package org.cardanofoundation.x402.facilitator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation id into the Log4j2 {@link ThreadContext} (MDC key
 * {@code correlationId}, surfaced by the log pattern) and echoes it on the
 * response, so every log line and error body for a request is traceable. Honors
 * an inbound {@code X-Correlation-Id} when the caller supplies a sane one, else
 * mints a UUID. Runs first so all downstream logging is tagged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final int MAX_LEN = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));
        if (correlationId == null) correlationId = UUID.randomUUID().toString();
        ThreadContext.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            ThreadContext.remove(MDC_KEY);
        }
    }

    /** Accepts only a short, safe token (no log-injection newlines / control chars). */
    private static String sanitize(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LEN) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = c == '-' || c == '_' || c == '.' || (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!ok) return null;
        }
        return value;
    }
}
