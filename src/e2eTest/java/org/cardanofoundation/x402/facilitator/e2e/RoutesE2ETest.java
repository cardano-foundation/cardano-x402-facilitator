package org.cardanofoundation.x402.facilitator.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP contract of every route, pinned against the running container: one
 * real verify + settle round trip, the exact /supported, /health and actuator
 * bodies, and every off-contract request (malformed, missing, oversize,
 * unregistered, wrong method/type/path) the facilitator answers today.
 */
@StackRequired
class RoutesE2ETest {

    private static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static final int MAX_REQUEST_BYTES = 65536;

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static FacilitatorClient mempoolFacilitator;
    private static Wallet payer;
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        mempoolFacilitator = FacilitatorClient.mempoolInstance();
        payer = kit.newWallet(100);
        receiver = kit.newWallet(5);
    }

    // ---- protocol routes: one real payment, verified then settled ----

    @Test
    void verifyThenSettleARealPayment() {
        Payment payment = payments.from(payer).payTo(receiver).lovelace(2_000_000).build();

        FacilitatorClient.Response verify = facilitator.verify(payment.body());
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        assertThat(verify.header("Content-Type")).startsWith("application/json");
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isTrue();
        assertThat(verified.path("payer").asText()).isEqualTo(payer.address());

        FacilitatorClient.Response settle = facilitator.settle(payment.body());
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isTrue();
        assertThat(settled.path("transaction").asText()).isEqualTo(payment.txHash());
        assertThat(settled.path("network").asText()).isEqualTo(DevnetKit.NETWORK);
        assertThat(settled.path("payer").asText()).isEqualTo(payer.address());
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        assertThat(settled.path("extra").path("transactionId").asText()).isEqualTo(payment.txHash());

        kit.assertOnChain(payment.txHash());
        // The facilitator broadcast exactly these bytes, exactly once, through the shim.
        assertThat(ShimClient.fromEnv().submitCount(payment.txBytes())).isEqualTo(1);
    }

    // ---- discovery and health ----

    @Test
    void supportedIsPinnedOnTheDefaultInstance() throws Exception {
        FacilitatorClient.Response response = facilitator.get("/supported");
        assertThat(response.status()).isEqualTo(200);
        SchemaAssert.assertMatches(SchemaAssert.SUPPORTED, response);
        assertThat(response.json()).isEqualTo(FacilitatorClient.JSON.readTree(supported(0)));
    }

    @Test
    void supportedAdvertisesMempoolAcceptanceOnTheMempoolInstance() throws Exception {
        FacilitatorClient.Response response = mempoolFacilitator.get("/supported");
        assertThat(response.status()).isEqualTo(200);
        SchemaAssert.assertMatches(SchemaAssert.SUPPORTED, response);
        assertThat(response.json()).isEqualTo(FacilitatorClient.JSON.readTree(supported(-1)));
    }

    @Test
    void healthIsOkWithTheDevnetNetwork() throws Exception {
        for (FacilitatorClient instance : new FacilitatorClient[]{facilitator, mempoolFacilitator}) {
            FacilitatorClient.Response response = instance.get("/health");
            assertThat(response.status()).as(instance.getBaseUrl()).isEqualTo(200);
            SchemaAssert.assertMatches(SchemaAssert.HEALTH, response);
            assertThat(response.json()).isEqualTo(FacilitatorClient.JSON.readTree(
                    "{\"status\":\"ok\",\"networks\":[{\"id\":\"cardano:preview\",\"required\":true,"
                            + "\"healthy\":true,\"detail\":\"ok\"}]}"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void actuatorProbesAreUp(String path) {
        FacilitatorClient.Response response = facilitator.get(path);
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.json().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void actuatorInfoIsExposed() {
        FacilitatorClient.Response response = facilitator.get("/actuator/info");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().isObject()).isTrue();
    }

    @Test
    void prometheusExposesJvmAndHttpMeters() {
        // Warm the http meter with a request of our own.
        facilitator.get("/supported");
        FacilitatorClient.Response response = facilitator.get("/actuator/prometheus");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/plain");
        // The facilitator registers no meters of its own: JVM + Spring's HTTP timers are the surface.
        assertThat(response.body())
                .contains("jvm_memory_used_bytes")
                .contains("http_server_requests_seconds_count{")
                .contains("uri=\"/supported\"");
    }

    // ---- correlation id ----

    @Test
    void aSaneCorrelationIdIsEchoed() {
        FacilitatorClient.Response response = facilitator.get("/supported", Map.of("X-Correlation-Id", "e2e-abc_1.2"));
        assertThat(response.header("X-Correlation-Id")).isEqualTo("e2e-abc_1.2");
    }

    @Test
    void aMissingCorrelationIdIsGenerated() {
        FacilitatorClient.Response response = facilitator.get("/supported");
        assertThat(response.header("X-Correlation-Id")).matches(UUID);
    }

    @Test
    void anUnsafeCorrelationIdIsReplaced() {
        FacilitatorClient.Response tooLong = facilitator.get("/supported", Map.of("X-Correlation-Id", "a".repeat(129)));
        FacilitatorClient.Response badChars = facilitator.get("/supported", Map.of("X-Correlation-Id", "x;y"));
        assertThat(tooLong.header("X-Correlation-Id")).matches(UUID);
        assertThat(badChars.header("X-Correlation-Id")).matches(UUID);
    }

    // ---- off-contract requests on /verify and /settle ----

    @ParameterizedTest
    @ValueSource(strings = {"/verify", "/settle"})
    void malformedJsonIs400(String path) {
        FacilitatorClient.Response response = facilitator.post(path, "{not json");
        assertError(response, 400, "Malformed request body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/verify", "/settle"})
    void missingPaymentPayloadIs400(String path) {
        Map<String, Object> body = validBody();
        body.remove("paymentPayload");
        assertError(facilitator.post(path, FacilitatorClient.toJson(body)), 400,
                "Missing paymentPayload or paymentRequirements");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/verify", "/settle"})
    void missingPaymentRequirementsIs400(String path) {
        Map<String, Object> body = validBody();
        body.remove("paymentRequirements");
        assertError(facilitator.post(path, FacilitatorClient.toJson(body)), 400,
                "Missing paymentPayload or paymentRequirements");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/verify", "/settle"})
    void aDeclaredBodyAbove64KiBIs413(String path) {
        FacilitatorClient.Response response = facilitator.post(path, oversizeBody());
        assertThat(response.status()).as("%s", response).isEqualTo(413);
        // Rejected on Content-Length before the controller: Spring's default error body, not ErrorResponse.
        assertThat(response.json().path("status").asInt()).isEqualTo(413);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/verify", "/settle"})
    void aStreamedBodyAbove64KiBIs413(String path) {
        FacilitatorClient.Response response = facilitator.postStreamed(path, oversizeBody().getBytes(StandardCharsets.UTF_8));
        assertError(response, 413, "Request body too large");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/verify", "/settle"})
    void anUnregisteredNetworkIs500(String path) {
        Map<String, Object> body = validBody();
        requirementsOf(body).put("network", "cardano:mainnet");
        assertError(facilitator.post(path, FacilitatorClient.toJson(body)), 500,
                "No facilitator registered for scheme: exact and network: cardano:mainnet");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/verify", "/settle"})
    void anUnsupportedVersionIs500(String path) {
        Map<String, Object> body = validBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> paymentPayload = (Map<String, Object>) body.get("paymentPayload");
        paymentPayload.put("x402Version", 1);
        // The registry is keyed by (version, scheme, network), so a v1 payload never reaches
        // the scheme's unsupported_version check — and the message names only scheme + network.
        assertError(facilitator.post(path, FacilitatorClient.toJson(body)), 500,
                "No facilitator registered for scheme: exact and network: cardano:preview");
    }

    // The three below are NOT a designed contract: ApiErrorAdvice's catch-all turns
    // Spring's 405 / 415 / 404 into 500 internal_error. Pinned so a change is
    // deliberate; listed as a docs/code gap in WP-2's Log.

    @Test
    void wrongMethodIs500InternalError() {
        assertInternalError(facilitator.get("/verify"));
    }

    @Test
    void wrongContentTypeIs500InternalError() {
        assertInternalError(facilitator.post("/verify", FacilitatorClient.toJson(validBody()), "text/plain"));
    }

    @Test
    void unknownPathIs500InternalError() {
        assertInternalError(facilitator.get("/does-not-exist"));
    }

    // ---- helpers ----

    /**
     * A well-formed request whose transaction is never broadcast: enough for the
     * envelope-level checks, which answer before any chain work.
     */
    private static Map<String, Object> validBody() {
        return new Payment(payer, new byte[]{0}, "00".repeat(32),
                Map.of("scheme", "exact", "network", DevnetKit.NETWORK, "asset", "lovelace",
                        "amount", "2000000", "payTo", receiver.address(),
                        "maxTimeoutSeconds", PaymentFactory.DEFAULT_MAX_TIMEOUT_SECONDS,
                        "extra", Map.of("assetTransferMethod", "default")),
                Map.of("transaction", "AA==", "nonce", "00".repeat(32) + "#0"))
                .body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requirementsOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("paymentRequirements");
    }

    private static String oversizeBody() {
        return "{\"pad\":\"" + "a".repeat(MAX_REQUEST_BYTES + 1024) + "\"}";
    }

    private static void assertError(FacilitatorClient.Response response, int status, String error) {
        assertThat(response.status()).as("%s", response).isEqualTo(status);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        SchemaAssert.assertMatches(SchemaAssert.ERROR, response);
        assertThat(response.json().path("error").asText()).isEqualTo(error);
        assertThat(response.header("X-Correlation-Id")).isNotBlank();
    }

    private static void assertInternalError(FacilitatorClient.Response response) {
        assertError(response, 500, "internal_error");
        assertThat(response.json().path("correlationId").asText()).matches(UUID);
    }

    private static String supported(int minimumConfirmations) {
        return "{\"kinds\":[{\"x402Version\":2,\"scheme\":\"exact\",\"network\":\"cardano:preview\","
                + "\"extra\":{\"assetTransferMethods\":[\"default\",\"masumi\",\"script\"],"
                + "\"areFeesSponsored\":false,"
                + "\"l1Confirmations\":{\"maximum\":20,\"minimum\":" + minimumConfirmations + "}}}],"
                + "\"extensions\":[],\"signers\":{\"cardano:*\":[]}}";
    }
}
