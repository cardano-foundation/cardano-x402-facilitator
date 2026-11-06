package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.E2eEnv;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shim's opt-in control API, against the live shim and Yano: every action, {@code times},
 * delete one / all, the profile, the submit exemption, and {@code /sdk} ignoring all of it.
 * With nothing registered the facilitator view is Yano, byte for byte.
 *
 * <p>{@code @Isolated}: the faults and the profile are path-wide on a shim the facilitators use.
 * Every test resets them in {@code finally}; IsolatedStackGuard does it once more afterwards.
 */
@Isolated
@StackRequired
class ShimFaultsE2ETest {

    private static final String API = "/api/v1";

    private static ShimClient shim;
    private static FacilitatorClient view;      // the facilitator's view of the shim
    private static FacilitatorClient direct;    // Yano itself
    private static String confirmedHash;
    private static int confirmedIndex;
    private static String confirmedOutRef;

    @BeforeAll
    static void stack() {
        shim = ShimClient.fromEnv();
        shim.reset();
        view = new FacilitatorClient(E2eEnv.shimUrl());
        direct = new FacilitatorClient(E2eEnv.chainUrl());
        DevnetKit kit = DevnetKit.fromEnv();
        Utxo utxo = kit.utxos(Funding.wallet(5).address()).get(0);
        confirmedHash = utxo.getTxHash();
        confirmedIndex = utxo.getOutputIndex();
        confirmedOutRef = confirmedHash + "#" + confirmedIndex;
    }

    // ---- pass-through ----

    @Test
    void withNothingRegisteredStableEndpointsEqualYanoByteForByte() {
        for (String path : List.of("/txs/" + confirmedHash, "/txs/" + confirmedHash + "/utxos")) {
            FacilitatorClient.Response viaShim = view.get(API + path);
            FacilitatorClient.Response yano = direct.get(path);
            assertThat(yano.status()).as("%s", yano).isEqualTo(200);
            assertThat(viaShim.status()).isEqualTo(yano.status());
            assertThat(viaShim.body()).as(path).isEqualTo(yano.body());
        }
    }

    // ---- read-path actions ----

    @Test
    void hideTxHidesTheTxAndItsSubPathsButNeverItsMempoolEntry() {
        try {
            shim.registerFault("hide", Map.of("txHash", confirmedHash), Map.of("type", "hide-tx"));
            assertThat(view.get(API + "/txs/" + confirmedHash).status()).isEqualTo(404);
            assertThat(view.get(API + "/txs/" + confirmedHash + "/utxos").status()).isEqualTo(404);
            assertThat(view.get(API + "/txs/" + confirmedHash + "/utxos").json().path("status_code").asInt()).isEqualTo(404);
            // The mempool path passes through: Yano's own answer (status only; its 404 body is not a stable endpoint).
            FacilitatorClient.Response mempool = view.get(API + "/mempool/" + confirmedHash);
            assertThat(mempool.status()).isEqualTo(direct.get("/mempool/" + confirmedHash).status());
            // Another tx is untouched, and so is the /sdk view of this one.
            assertThat(view.get(API + "/txs/" + "0".repeat(64)).status()).isEqualTo(direct.get("/txs/" + "0".repeat(64)).status());
            assertThat(view.get("/sdk" + API + "/txs/" + confirmedHash).status()).isEqualTo(200);
        } finally {
            shim.reset();
        }
        assertThat(view.get(API + "/txs/" + confirmedHash).status()).isEqualTo(200);
    }

    @Test
    void mempoolAnswers200OnlyForItsHashAndHideTxTogetherWithItLeavesItAlone() {
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        assertThat(view.get(API + "/mempool/" + hash).status()).as("baseline: not in the mempool").isEqualTo(404);
        try {
            shim.registerFault("mempool", Map.of("txHash", hash), Map.of("type", "mempool"));
            shim.registerFault("hide", Map.of("txHash", hash), Map.of("type", "hide-tx"));
            assertThat(view.get(API + "/mempool/" + hash).status()).isEqualTo(200);
            assertThat(view.get(API + "/txs/" + hash).status()).isEqualTo(404);
            assertThat(view.get(API + "/txs/" + hash + "/utxos").status()).isEqualTo(404);
            String other = "f".repeat(64);
            assertThat(view.get(API + "/mempool/" + other).status()).isEqualTo(404);
            // The mempool fault alone says nothing about /txs.
            shim.deleteFault("hide");
            assertThat(view.get(API + "/txs/" + hash).status()).isEqualTo(404); // Yano's own: no such tx
            assertThat(view.get(API + "/mempool/" + hash).status()).isEqualTo(200);
        } finally {
            shim.reset();
        }
        assertThat(view.get(API + "/mempool/" + hash).status()).isEqualTo(404);
    }

    @Test
    void statusAnswersTheGivenStatusWithoutForwardingAndTimesLimitsIt() {
        try {
            shim.registerFault("s503", Map.of("txHash", confirmedHash), Map.of("type", "status", "status", 503), 2);
            FacilitatorClient.Response first = view.get(API + "/txs/" + confirmedHash);
            assertThat(first.status()).isEqualTo(503);
            assertThat(first.json().path("status_code").asInt()).isEqualTo(503);
            assertThat(view.get(API + "/txs/" + confirmedHash).status()).isEqualTo(503);
            assertThat(view.get(API + "/txs/" + confirmedHash).status()).as("times exhausted").isEqualTo(200);
        } finally {
            shim.reset();
        }
    }

    @Test
    void aStatusFaultCanCarryItsOwnBodyAndMatchByPathRegex() {
        try {
            shim.registerFault("regex", Map.of("pathRegex", "/txs/" + confirmedHash + "$"),
                    Map.of("type", "status", "status", 418, "body", "{\"custom\":true}"));
            FacilitatorClient.Response r = view.get(API + "/txs/" + confirmedHash);
            assertThat(r.status()).isEqualTo(418);
            assertThat(r.json().path("custom").asBoolean()).isTrue();
            assertThat(view.get(API + "/txs/" + confirmedHash + "/utxos").status()).as("regex is anchored").isEqualTo(200);
        } finally {
            shim.reset();
        }
    }

    @Test
    void deleteRemovesOneFaultOrAll() {
        try {
            shim.registerFault("a", Map.of("txHash", confirmedHash), Map.of("type", "status", "status", 500));
            shim.registerFault("b", Map.of("pathRegex", "/txs/" + confirmedHash + "/utxos"), Map.of("type", "status", "status", 502));
            assertThat(view.get(API + "/txs/" + confirmedHash).status()).isEqualTo(500);
            shim.deleteFault("a");
            assertThat(view.get(API + "/txs/" + confirmedHash).status()).isEqualTo(200);
            assertThat(view.get(API + "/txs/" + confirmedHash + "/utxos").status()).isEqualTo(502);
            shim.registerFault("a", Map.of("txHash", confirmedHash), Map.of("type", "status", "status", 500));
            shim.clearFaults();
            assertThat(view.get(API + "/txs/" + confirmedHash).status()).isEqualTo(200);
            assertThat(view.get(API + "/txs/" + confirmedHash + "/utxos").status()).isEqualTo(200);
        } finally {
            shim.reset();
        }
    }

    @Test
    void dropLovelaceRemovesOnlyTheLovelaceEntryOfThatOutput() {
        JsonNode before = direct.get("/txs/" + confirmedHash + "/utxos").json();
        try {
            shim.registerFault("drop", Map.of("outRef", confirmedOutRef), Map.of("type", "drop-lovelace"));
            FacilitatorClient.Response r = view.get(API + "/txs/" + confirmedHash + "/utxos");
            assertThat(r.status()).isEqualTo(200);
            JsonNode after = r.json();
            assertThat(after.path("outputs")).hasSameSizeAs(before.path("outputs"));
            for (int i = 0; i < before.path("outputs").size(); i++) {
                JsonNode was = before.path("outputs").get(i);
                JsonNode now = after.path("outputs").get(i);
                if (was.path("output_index").asInt() == confirmedIndex) {
                    assertThat(units(was)).contains("lovelace");
                    assertThat(units(now)).doesNotContain("lovelace");
                    assertThat(now.path("address").asText()).isEqualTo(was.path("address").asText());
                } else {
                    assertThat(now).isEqualTo(was);
                }
            }
            assertThat(after.path("inputs")).isEqualTo(before.path("inputs"));
        } finally {
            shim.reset();
        }
    }

    // ---- submit-path actions ----

    @Test
    void submitFaultsMatchBySha256AndEverySubmitBodyIsCountedFaulted() {
        byte[] swallowed = junk();
        byte[] rejected = junk();
        byte[] dropped = junk();
        byte[] plain = junk();
        String hash = "a".repeat(64);
        try {
            shim.registerFault("swallow", Map.of("sha256", ShimClient.sha256Hex(swallowed)),
                    Map.of("type", "swallow", "txHash", hash));
            shim.registerFault("reject", Map.of("sha256", ShimClient.sha256Hex(rejected)),
                    Map.of("type", "ledger-reject", "body", "{\"message\":\"ApplyTxError boom\"}"));
            shim.registerFault("drop", Map.of("sha256", ShimClient.sha256Hex(dropped)), Map.of("type", "drop-response"));

            FacilitatorClient.Response s = submit(swallowed);
            assertThat(s.status()).isEqualTo(200);
            assertThat(s.json().asText()).isEqualTo(hash);
            FacilitatorClient.Response r = submit(rejected);
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body()).contains("ApplyTxError boom");
            assertThat(submit(dropped).status()).isEqualTo(502);
            // An unfaulted body is forwarded: Yano refuses the junk itself, in its own words.
            FacilitatorClient.Response p = submit(plain);
            assertThat(p.status()).isNotEqualTo(200).isNotEqualTo(502);

            for (byte[] body : List.of(swallowed, rejected, dropped, plain)) {
                assertThat(shim.submitCount(body)).as("counted").isEqualTo(1);
            }
        } finally {
            shim.reset();
        }
    }

    @Test
    void aSubmitFaultWithTimesFiresThatOftenAndThenPassesThrough() {
        byte[] body = junk();
        try {
            shim.registerFault("once", Map.of("sha256", ShimClient.sha256Hex(body)),
                    Map.of("type", "ledger-reject", "body", "ApplyTxError once"), 1);
            assertThat(submit(body).status()).isEqualTo(400);
            assertThat(submit(body).body()).doesNotContain("ApplyTxError once");
            assertThat(shim.submitCount(body)).isEqualTo(2);
        } finally {
            shim.reset();
        }
    }

    // ---- profile ----

    @Test
    void aLatencyAloneDelaysEveryRequestAndNeverRateLimits() {
        try {
            shim.setProfile(300, null, null);
            for (int i = 0; i < 12; i++) {
                long started = System.nanoTime();
                FacilitatorClient.Response r = view.get(API + "/txs/" + confirmedHash);
                long millis = (System.nanoTime() - started) / 1_000_000;
                assertThat(r.status()).as("request %d: %s", i, r).isEqualTo(200);
                assertThat(millis).as("request %d", i).isGreaterThanOrEqualTo(290);
            }
            long started = System.nanoTime();
            view.get("/sdk" + API + "/txs/" + confirmedHash);
            assertThat((System.nanoTime() - started) / 1_000_000).as("/sdk is not delayed").isLessThan(290);
        } finally {
            shim.reset();
        }
        long started = System.nanoTime();
        view.get(API + "/txs/" + confirmedHash);
        assertThat((System.nanoTime() - started) / 1_000_000).as("profile cleared").isLessThan(290);
    }

    @Test
    void overTheRateTheShimAnswers429ButNeverForASubmitOrTheSdkView() {
        List<Integer> statuses = new ArrayList<>();
        try {
            shim.setProfile(null, 1.0, 2);
            // The facilitators' reconcilers may take tokens too, so only bounds are asserted: the
            // first request (full bucket) passes and a 429 shows up within a bounded burst.
            FacilitatorClient.Response limited = null;
            for (int i = 0; i < 40 && limited == null; i++) {
                FacilitatorClient.Response r = view.get(API + "/txs/" + confirmedHash);
                statuses.add(r.status());
                if (r.status() == 429) limited = r;
            }
            assertThat(statuses.get(0)).as("the burst passes").isEqualTo(200);
            assertThat(limited).as("a 429 within 40 rapid requests: %s", statuses).isNotNull();
            assertThat(limited.json().path("status_code").asInt()).isEqualTo(429);
            assertThat(limited.json().path("error").asText()).isNotEmpty();
            assertThat(limited.json().path("message").asText()).isNotEmpty();
            // Exempt: a submit, even with the bucket empty (Yano refuses the junk with something other than 429).
            for (int i = 0; i < 4; i++) assertThat(submit(junk()).status()).isNotEqualTo(429);
            for (int i = 0; i < 6; i++) {
                assertThat(view.get("/sdk" + API + "/txs/" + confirmedHash).status()).isEqualTo(200);
            }
        } finally {
            shim.reset();
        }
        assertThat(view.get(API + "/txs/" + confirmedHash).status()).isEqualTo(200);
    }

    // ---- scope ----

    @Test
    void theSdkViewIgnoresFaults() {
        try {
            shim.registerFault("all", Map.of("pathRegex", ".*"), Map.of("type", "status", "status", 500));
            assertThat(view.get(API + "/txs/" + confirmedHash).status()).isEqualTo(500);
            assertThat(view.get("/sdk" + API + "/txs/" + confirmedHash).status()).isEqualTo(200);
        } finally {
            shim.reset();
        }
    }

    @Test
    void aMalformedFaultOrProfileIsRefused() {
        FacilitatorClient raw = new FacilitatorClient(E2eEnv.shimUrl());
        try {
            List<String> malformedFaults = List.of(
                    "{\"id\":\"x\",\"match\":{},\"action\":{\"type\":\"hide-tx\"}}",
                    "{\"id\":\"x\",\"match\":{\"txHash\":\"a\"},\"action\":{\"type\":\"nope\"}}",
                    "{\"id\":\"x\",\"match\":{\"pathRegex\":\"(\"},\"action\":{\"type\":\"hide-tx\"}}",
                    "{\"id\":\"x\",\"match\":{\"txHash\":123},\"action\":{\"type\":\"status\",\"status\":500}}",
                    "{\"id\":\"x\",\"match\":{\"txHash\":\"a\"},\"action\":{\"type\":\"swallow\"}}",
                    "{\"id\":\"x\",\"match\":{\"sha256\":\"a\"},\"action\":{\"type\":\"ledger-reject\"}}",
                    "{\"id\":\"x\",\"match\":{\"txHash\":\"a\"},\"action\":{\"type\":\"status\"}}",
                    "{\"id\":\"x\",\"match\":{\"txHash\":\"a\"},\"action\":{\"type\":\"status\",\"status\":\"429\"}}");
            for (String fault : malformedFaults) {
                assertThat(raw.post("/__shim/faults", fault).status()).as(fault).isEqualTo(400);
            }
            assertThat(raw.post("/__shim/profile", "{\"latency\":5}").status()).isEqualTo(400);
            assertThat(view.get(API + "/txs/" + confirmedHash).status()).as("nothing malformed got in").isEqualTo(200);
        } finally {
            shim.reset();
        }
    }

    // ---- helpers ----

    private static List<String> units(JsonNode output) {
        List<String> units = new ArrayList<>();
        for (JsonNode amount : output.path("amount")) units.add(amount.path("unit").asText());
        return units;
    }

    private static byte[] junk() {
        return ("shim-faults-test-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    /** POSTs the bytes as a tx submit; the body is ASCII so the shim's SHA-256 equals ShimClient's. */
    private static FacilitatorClient.Response submit(byte[] body) {
        return view.post(API + "/tx/submit", new String(body, StandardCharsets.UTF_8), "application/cbor");
    }
}
