package org.cardanofoundation.x402.facilitator.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGuardFilterTest {

    static X402Properties props(List<String> keys, Integer rpm) {
        return new X402Properties(null, null, null, null, null, null,
                new X402Properties.Security(keys, rpm == null ? null : new X402Properties.Security.RateLimit(rpm)));
    }

    static MockHttpServletRequest req(String path, String apiKey) {
        return req(path, apiKey, "10.0.0.1");
    }

    static MockHttpServletRequest req(String path, String apiKey, String remoteAddr) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setServletPath(path);
        r.setRemoteAddr(remoteAddr);
        if (apiKey != null) r.addHeader("X-API-Key", apiKey);
        return r;
    }

    /** A clock whose instant can be advanced, to drive the fixed-window rollover. */
    static final class MutableClock extends Clock {
        volatile long millis;
        MutableClock(long millis) { this.millis = millis; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    @Test void rejectsMissingApiKeyOnGuardedPath() throws Exception {
        ApiGuardFilter filter = new ApiGuardFilter(props(List.of("secret"), null), Clock.systemUTC());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req("/verify", null), res, chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull(); // did not pass through
    }

    @Test void rejectsWrongApiKey() throws Exception {
        ApiGuardFilter filter = new ApiGuardFilter(props(List.of("secret"), null), Clock.systemUTC());
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req("/settle", "nope"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test void allowsCorrectApiKey() throws Exception {
        ApiGuardFilter filter = new ApiGuardFilter(props(List.of("secret"), null), Clock.systemUTC());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req("/verify", "secret"), res, chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull(); // passed through
    }

    @Test void leavesUnguardedPathsOpen() throws Exception {
        ApiGuardFilter filter = new ApiGuardFilter(props(List.of("secret"), null), Clock.systemUTC());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req("/supported", null), res, chain); // no key, but not guarded
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test void enforcesRateLimitPerBucket() throws Exception {
        // 2 requests/minute; the 3rd in the same minute is throttled.
        ApiGuardFilter filter = new ApiGuardFilter(props(List.of(), 2), Clock.systemUTC());
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(req("/verify", null), ok, new MockFilterChain());
            assertThat(ok.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        MockHttpServletResponse throttled = new MockHttpServletResponse();
        filter.doFilter(req("/verify", null), throttled, new MockFilterChain());
        assertThat(throttled.getStatus()).isEqualTo(429);
        assertThat(throttled.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test void evictsStaleBucketsWhenMinuteRolls() throws Exception {
        // Distinct client IPs must not accumulate windows forever: once a new minute
        // starts, prior-minute buckets are swept so the map stays bounded.
        MutableClock clock = new MutableClock(60_000L); // minute 1
        ApiGuardFilter filter = new ApiGuardFilter(props(List.of(), 5), clock);
        for (int i = 0; i < 3; i++) {
            filter.doFilter(req("/verify", null, "10.0.0." + i), new MockHttpServletResponse(), new MockFilterChain());
        }
        assertThat(filter.trackedBucketCount()).isEqualTo(3);

        clock.millis = 120_000L; // minute 2: the next request triggers the sweep
        filter.doFilter(req("/verify", null, "10.0.0.99"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(filter.trackedBucketCount()).isEqualTo(1); // the 3 stale buckets dropped
    }
}
