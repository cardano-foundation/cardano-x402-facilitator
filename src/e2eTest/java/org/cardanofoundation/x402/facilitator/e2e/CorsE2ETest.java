package org.cardanofoundation.x402.facilitator.e2e;

import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS is default-deny (CorsConfig): the default instance sends no CORS headers; the
 * mempool instance (X402_HTTP_CORSALLOWEDORIGINS) allows exactly one origin on
 * /verify, /settle (POST) and /supported (GET). The header is asserted, not the status
 * Spring gives a rejected preflight.
 */
@StackRequired
class CorsE2ETest {

    private static final String ALLOWED = "https://allowed.e2e.test";
    private static final String OTHER = "https://other.e2e.test";
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";

    private final FacilitatorClient configured = FacilitatorClient.mempoolInstance();
    private final FacilitatorClient byDefault = FacilitatorClient.defaultInstance();

    private static FacilitatorClient.Response preflight(FacilitatorClient client, String path, String origin, String method) {
        return client.send("OPTIONS", path, null, Map.of(
                "Origin", origin,
                "Access-Control-Request-Method", method,
                "Access-Control-Request-Headers", "content-type"));
    }

    @Test
    void aConfiguredOriginMayPostToVerify() {
        FacilitatorClient.Response r = preflight(configured, "/verify", ALLOWED, "POST");
        assertThat(r.header(ALLOW_ORIGIN)).as("%s", r).isEqualTo(ALLOWED);
        assertThat(r.header(ALLOW_METHODS)).as("%s", r).contains("POST");
    }

    @Test
    void aConfiguredOriginMayPostToSettle() {
        FacilitatorClient.Response r = preflight(configured, "/settle", ALLOWED, "POST");
        assertThat(r.header(ALLOW_ORIGIN)).as("%s", r).isEqualTo(ALLOWED);
        assertThat(r.header(ALLOW_METHODS)).as("%s", r).contains("POST");
    }

    @Test
    void aConfiguredOriginMayGetSupported() {
        FacilitatorClient.Response r = preflight(configured, "/supported", ALLOWED, "GET");
        assertThat(r.header(ALLOW_ORIGIN)).as("%s", r).isEqualTo(ALLOWED);
        assertThat(r.header(ALLOW_METHODS)).as("%s", r).contains("GET");
    }

    @Test
    void anotherOriginGetsNoAllowOriginHeader() {
        for (String path : new String[]{"/verify", "/settle"}) {
            FacilitatorClient.Response r = preflight(configured, path, OTHER, "POST");
            assertThat(r.header(ALLOW_ORIGIN)).as("%s %s", path, r).isNull();
        }
        FacilitatorClient.Response r = preflight(configured, "/supported", OTHER, "GET");
        assertThat(r.header(ALLOW_ORIGIN)).as("%s", r).isNull();
    }

    @Test
    void theDefaultInstanceSendsNoAllowOriginHeaderToAnyOrigin() {
        for (String origin : new String[]{ALLOWED, OTHER}) {
            for (String path : new String[]{"/verify", "/settle"}) {
                FacilitatorClient.Response r = preflight(byDefault, path, origin, "POST");
                assertThat(r.header(ALLOW_ORIGIN)).as("%s %s %s", origin, path, r).isNull();
            }
            FacilitatorClient.Response supported = preflight(byDefault, "/supported", origin, "GET");
            assertThat(supported.header(ALLOW_ORIGIN)).as("%s", supported).isNull();
        }
    }
}
