package org.cardanofoundation.x402.facilitator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Pre-deserialization request-size cap: fast-reject on Content-Length, and
 * bound chunked/streamed bodies with a byte-counting
 * stream that aborts with 413 the moment the limit is crossed — oversized
 * bodies never reach Jackson.
 */
@Component
public class RequestSizeFilter extends OncePerRequestFilter {

    private final int maxBytes;

    public RequestSizeFilter(X402Properties props) {
        this.maxBytes = props.http() == null ? 65536 : props.http().maxRequestBytesOrDefault();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large");
            return;
        }
        filterChain.doFilter(new BoundedRequest(request, maxBytes), response);
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final int maxBytes;

        BoundedRequest(HttpServletRequest request, int maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long count;

                private void bump(long n) throws IOException {
                    if (n > 0) {
                        count += n;
                        if (count > maxBytes) {
                            throw new RequestTooLargeException();
                        }
                    }
                }

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) bump(1);
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = delegate.read(b, off, len);
                    bump(n);
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    delegate.setReadListener(readListener);
                }
            };
        }
    }

    /** Translated to 413 by the advice below (thrown mid-body-read, after headers). */
    public static final class RequestTooLargeException extends IOException {
        public RequestTooLargeException() {
            super("Request body exceeds limit");
        }
    }
}
