package org.cardanofoundation.x402.facilitator.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.log4j.Log4j2;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.Poll;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The facilitator's chain backend goes away (the shim in front of the chain API
 * is frozen with {@code docker compose pause}) and comes back. While it is gone:
 * health and readiness report it, verification fails closed, fresh settlement is
 * refused with 503, and a journaled payment is answered pending — never
 * rebroadcast. After it returns, the facilitator recovers on its own.
 *
 * <p>Tagged {@code outage}: e2e/run.sh runs it alone, last, because a paused shim
 * would break every other test running at the same time.
 */
@Tag("outage")
@StackRequired
@Log4j2
class ChainOutageE2ETest {

    /**
     * Paused calls hang until the provider's client timeout (OkHttp's ~10 s
     * defaults), /health caches for 5 s, and a re-settle can make three blocking
     * provider calls in a row — so every outage deadline is generous.
     */
    private static final Duration OUTAGE_DEADLINE = Duration.ofSeconds(180);

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static ShimClient shim;
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        shim = ShimClient.fromEnv();
        receiver = kit.newWallet(5);
    }

    @AfterAll
    static void neverLeaveTheShimPaused() {
        compose("unpause", "shim"); // idempotent safety net; the test unpauses in finally
    }

    @Test
    void anOutageDegradesAndRecovers() {
        // ---- before: one payment confirmed (its payload kept), one built but unsettled ----
        Payment confirmed = payments.from(kit.newWallet(10)).payTo(receiver).lovelace(2_000_000).build();
        FacilitatorClient.Response first = facilitator.settle(confirmed.body());
        assertThat(first.json().path("extra").path("status").asText()).as("%s", first).isEqualTo("confirmed");
        // TTL near the maxTimeoutSeconds bound: it must still be valid after the outage.
        Payment fresh = payments.from(kit.newWallet(10)).payTo(receiver).lovelace(2_000_000).ttlSlots(550).build();

        try {
            compose("pause", "shim");

            // ---- during ----
            JsonNode health = Poll.until("/health 503", OUTAGE_DEADLINE, () -> {
                FacilitatorClient.Response r = facilitator.get("/health");
                return r.status() == 503 ? Optional.of(r.json()) : Optional.empty();
            });
            SchemaAssert.assertMatches(SchemaAssert.HEALTH, health);
            assertThat(health.path("status").asText()).isEqualTo("unavailable");
            assertThat(health.path("networks").get(0).path("healthy").asBoolean()).isFalse();

            FacilitatorClient.Response readiness = facilitator.get("/actuator/health/readiness");
            assertThat(readiness.status()).as("%s", readiness).isEqualTo(503);
            assertThat(readiness.json().path("status").asText()).isEqualTo("DOWN");
            FacilitatorClient.Response liveness = facilitator.get("/actuator/health/liveness");
            assertThat(liveness.status()).as("%s", liveness).isEqualTo(200);
            assertThat(liveness.json().path("status").asText()).isEqualTo("UP");

            FacilitatorClient.Response verify = facilitator.verify(fresh.body());
            assertThat(verify.status()).as("%s", verify).isEqualTo(200);
            JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
            assertThat(verified.path("isValid").asBoolean()).isFalse();
            assertThat(verified.path("invalidReason").asText()).isEqualTo("exact_cardano_facilitator_chain_lookup_failed");

            // A fresh payment is refused by the health gate before any chain work.
            FacilitatorClient.Response freshSettle = facilitator.settle(fresh.body());
            assertThat(freshSettle.status()).as("%s", freshSettle).isEqualTo(503);
            JsonNode refused = SchemaAssert.assertMatches(SchemaAssert.SETTLE, freshSettle);
            assertThat(refused.path("success").asBoolean()).isFalse();
            assertThat(refused.path("errorReason").asText()).isEqualTo("exact_cardano_facilitator_chain_lookup_failed");
            assertThat(refused.path("transaction").asText()).isEmpty();

            // A journaled payment passes the gate and is answered pending: the evidence
            // is unknown, and unknown is never absence (so never a rebroadcast).
            FacilitatorClient.Response retry = facilitator.settle(confirmed.body());
            assertThat(retry.status()).as("%s", retry).isEqualTo(200);
            JsonNode pending = SchemaAssert.assertMatches(SchemaAssert.SETTLE, retry);
            assertThat(pending.path("success").asBoolean()).isFalse();
            assertThat(pending.path("errorReason").asText()).isEqualTo("settlement_pending");
            assertThat(pending.path("transaction").asText()).isEqualTo(confirmed.txHash());
            assertThat(pending.path("extra").path("status").asText()).isEqualTo("pending");
        } finally {
            compose("unpause", "shim");
        }

        // ---- after ----
        Poll.until("readiness UP after the outage", OUTAGE_DEADLINE, () -> {
            FacilitatorClient.Response r = facilitator.get("/actuator/health/readiness");
            return r.status() == 200 && "UP".equals(r.json().path("status").asText()) ? Optional.of(true) : Optional.empty();
        });
        assertThat(shim.submitCount(confirmed.txBytes())).as("the journaled payment was never rebroadcast").isEqualTo(1);
        assertThat(shim.submitCount(fresh.txBytes())).as("the refused payment was never broadcast").isZero();

        FacilitatorClient.Response recovered = facilitator.settle(fresh.body());
        assertThat(recovered.status()).as("%s", recovered).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, recovered);
        assertThat(settled.path("success").asBoolean()).as("%s", recovered).isTrue();
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        assertThat(settled.path("transaction").asText()).isEqualTo(fresh.txHash());
        kit.assertOnChain(fresh.txHash());

        // The journaled payment is confirmed again once evidence is back.
        FacilitatorClient.Response again = facilitator.settle(confirmed.body());
        assertThat(again.json().path("success").asBoolean()).as("%s", again).isTrue();
        assertThat(again.json().path("extra").path("status").asText()).isEqualTo("confirmed");
    }

    /** Runs {@code docker compose -p $E2E_COMPOSE_PROJECT -f $E2E_COMPOSE_FILE <args>}; fails loudly. */
    private static void compose(String... args) {
        String project = requiredEnv("E2E_COMPOSE_PROJECT");
        String file = requiredEnv("E2E_COMPOSE_FILE");
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", project, "-f", file));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("timed out: " + command);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // unpause of a running container is not an error worth failing on
            if (process.exitValue() != 0 && !(args[0].equals("unpause") && output.contains("is not paused"))) {
                throw new IllegalStateException(command + " exited " + process.exitValue() + ": " + output);
            }
            log.info("{} → {}", String.join(" ", args), output.strip());
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + command, e);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set — run the suite through e2e/run.sh --java");
        }
        return value;
    }
}
