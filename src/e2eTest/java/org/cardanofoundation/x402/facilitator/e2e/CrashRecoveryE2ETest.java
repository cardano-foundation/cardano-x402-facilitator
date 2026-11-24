package org.cardanofoundation.x402.facilitator.e2e;

import lombok.extern.log4j.Log4j2;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.Poll;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Stack;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Durability under crashes: a facilitator killed mid-settle, and a Postgres that goes away,
 * never cause a second broadcast or a success without inclusion.
 *
 * <p>{@code @Isolated}: it kills and stops containers that every other class depends on.
 * Each test restores the stack in {@code finally}; IsolatedStackGuard double-checks.
 */
@Isolated
@StackRequired
@Log4j2
class CrashRecoveryE2ETest {

    private static final Duration FACILITATOR_BOOT = Duration.ofSeconds(240);
    private static final Duration DOWN_BOUND = Duration.ofSeconds(45);
    private static final Duration RECONCILE_DEADLINE = Duration.ofSeconds(90);
    /** Journal of the default facilitator: database {@code postgres}, schema {@code facilitator}. */
    private static final String DEFAULT_DB = "postgres";

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static FacilitatorClient mempoolFacilitator;
    private static ShimClient shim;
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        mempoolFacilitator = FacilitatorClient.mempoolInstance();
        shim = ShimClient.fromEnv();
        receiver = kit.newWallet(5);
    }

    @Test
    void aRestartMidSettleNeverRebroadcastsAndTheReconcilerConfirms() {
        Payment first = payments.from(Funding.wallet(10)).payTo(receiver).lovelace(2_000_000).build();
        try {
            // ---- first payment: interrupted, then re-settled by the client ----
            CompletableFuture<Object> firstSettle = interruptMidSettle(first, "crash-first");
            assertThat(shim.submitCount(first.txBytes())).as("one submit before the restart").isEqualTo(1);
            killMidSettle(first, firstSettle);
            Stack.start("facilitator");
            awaitReadiness("facilitator UP after restart", facilitator, true, FACILITATOR_BOOT);
            shim.deleteFault("crash-first");

            FacilitatorClient.Response again = facilitator.settle(first.body());
            assertThat(again.status()).as("%s", again).isEqualTo(200);
            assertThat(again.json().path("success").asBoolean()).as("%s", again).isTrue();
            assertThat(again.json().path("extra").path("status").asText()).as("%s", again).isEqualTo("confirmed");
            assertThat(again.json().path("transaction").asText()).isEqualTo(first.txHash());
            assertThat(shim.submitCount(first.txBytes())).as("the re-settle never rebroadcast").isEqualTo(1);
            kit.assertOnChain(first.txHash());

            // ---- second payment: interrupted, never re-settled; the reconciler must confirm it ----
            // built only now: a slow reboot above must not eat its TTL
            Payment second = payments.from(Funding.wallet(10)).payTo(receiver).lovelace(2_000_000).build();
            CompletableFuture<Object> secondSettle = interruptMidSettle(second, "crash-second");
            assertThat(shim.submitCount(second.txBytes())).isEqualTo(1);
            killMidSettle(second, secondSettle);
            Stack.start("facilitator");
            awaitReadiness("facilitator UP after second restart", facilitator, true, FACILITATOR_BOOT);
            long readyAt = System.nanoTime();
            shim.deleteFault("crash-second");

            String status = Poll.until("journal row CONFIRMED without a re-settle", RECONCILE_DEADLINE, () -> {
                String s = Stack.psql(DEFAULT_DB, "SELECT status FROM facilitator.settlement WHERE tx_hash = '" + second.txHash() + "'");
                return "CONFIRMED".equals(s) ? Optional.of(s) : Optional.empty();
            });
            note("RECONCILER_CONFIRMED_AFTER_READY_MS=" + Duration.ofNanos(System.nanoTime() - readyAt).toMillis() + " status=" + status);
            assertThat(shim.submitCount(second.txBytes())).as("the reconciler never rebroadcast").isEqualTo(1);
            kit.assertOnChain(second.txHash());
        } finally {
            restoreStack();
        }
    }

    @Test
    void aPostgresOutageFailsClosedAndRecovers() {
        Payment fresh = payments.from(Funding.wallet(10)).payTo(receiver).lovelace(2_000_000).build(); // default TTL 300
        try {
            // Ruling: the db probe can block for Hikari's default 30 s connectionTimeout, so the
            // bound is 45 s (30 s + 15 s slack), counted from when `stop` returns, and enforced.
            Stack.stop("postgres");
            long stoppedAt = System.nanoTime();
            awaitReadiness("readiness DOWN after the postgres stop", facilitator, false, DOWN_BOUND);
            Duration down = Duration.ofNanos(System.nanoTime() - stoppedAt);
            note("READINESS_DOWN_AFTER_STOP_RETURNED_MS=" + down.toMillis());
            assertThat(down).as("readiness DOWN after stop returned").isLessThanOrEqualTo(DOWN_BOUND);

            FacilitatorClient.Response verify = facilitator.verify(fresh.body());
            note("VERIFY_DURING_POSTGRES_OUTAGE=" + verify);

            boolean succeeded;
            FacilitatorClient.Response settle = null;
            try {
                settle = facilitator.settle(fresh.body());
                succeeded = settle.json().path("success").asBoolean(false);
            } catch (RuntimeException e) {
                note("SETTLE_DURING_POSTGRES_OUTAGE threw " + e);
                succeeded = false; // no answer is also not a success
            }
            note("SETTLE_DURING_POSTGRES_OUTAGE=" + settle);
            assertThat(succeeded).as("settle during the outage: %s", settle).isFalse();
            assertThat(shim.submitCount(fresh.txBytes())).as("no claim, so no broadcast").isZero();

            long startAt = System.nanoTime();
            Stack.start("postgres");
            awaitReadiness("readiness UP after the postgres start", facilitator, true, Duration.ofSeconds(60));
            note("READINESS_UP_AFTER_START_MS=" + Duration.ofNanos(System.nanoTime() - startAt).toMillis());

            FacilitatorClient.Response recovered = facilitator.settle(fresh.body());
            assertThat(recovered.status()).as("%s", recovered).isEqualTo(200);
            assertThat(recovered.json().path("success").asBoolean()).as("%s", recovered).isTrue();
            assertThat(recovered.json().path("extra").path("status").asText()).as("%s", recovered).isEqualTo("confirmed");
            assertThat(shim.submitCount(fresh.txBytes())).isEqualTo(1);
            kit.assertOnChain(fresh.txHash());
        } finally {
            restoreStack();
        }
    }

    /** Measurements for the work-package Log: stdout lands in the Gradle XML report. */
    private static void note(String line) {
        System.out.println(line);
    }

    /** Hides the tx from the shim's lookups, starts /settle in the background, and waits for its submit. */
    private static CompletableFuture<Object> interruptMidSettle(Payment payment, String faultId) {
        shim.registerFault(faultId, Map.of("txHash", payment.txHash()), Map.of("type", "hide-tx"));
        CompletableFuture<Object> background = CompletableFuture.supplyAsync(() -> {
            try {
                return facilitator.settle(payment.body());
            } catch (RuntimeException e) {
                return e; // the kill severs the connection
            }
        });
        Poll.until("the submit reaches the shim", Duration.ofSeconds(60),
                () -> shim.submitCount(payment.txBytes()) >= 1 ? Optional.of(true) : Optional.empty());
        assertThat(background.isDone()).as("settle must still be waiting for inclusion").isFalse();
        return background;
    }

    /**
     * Kills the facilitator mid-settle. Before the kill the journal row must be in flight (not
     * CONFIRMED); the interrupted settle, if it answered at all, never reported success.
     */
    private static void killMidSettle(Payment payment, CompletableFuture<Object> background) {
        String status = Stack.psql(DEFAULT_DB, "SELECT status FROM facilitator.settlement WHERE tx_hash = '" + payment.txHash() + "'");
        assertThat(status).as("journal row before the kill").isIn("SUBMITTING", "SUBMITTED");
        Stack.kill("facilitator");
        Object outcome;
        try {
            outcome = background.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return; // never answered: not a success either
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
        // A success is a 200 settle body; parse it rather than match text, so formatting can't hide one.
        if (outcome instanceof FacilitatorClient.Response response && response.status() == 200) {
            assertThat(response.json().path("success").asBoolean(false))
                    .as("interrupted settle answer: %s", response).isFalse();
        }
    }

    private static void awaitReadiness(String what, FacilitatorClient instance, boolean up, Duration deadline) {
        Poll.until(what, deadline, () -> {
            FacilitatorClient.Response r = instance.get("/actuator/health/readiness");
            boolean isUp = r.status() == 200 && "UP".equals(r.json().path("status").asText());
            return isUp == up ? Optional.of(true) : Optional.empty();
        });
    }

    /** Containers running, both facilitators UP, shim faults cleared. Each step runs even if another fails. */
    private static void restoreStack() {
        RuntimeException failure = null;
        try {
            Stack.upNoRecreate("postgres");
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            Stack.upNoRecreate("facilitator");
            Stack.upNoRecreate("facilitator-mempool");
        } catch (RuntimeException e) {
            if (failure != null) e.addSuppressed(failure);
            failure = e;
        }
        try {
            shim.reset();
        } catch (RuntimeException e) {
            if (failure != null) e.addSuppressed(failure);
            failure = e;
        }
        try {
            awaitReadiness("default facilitator UP", facilitator, true, FACILITATOR_BOOT);
            awaitReadiness("mempool facilitator UP", mempoolFacilitator, true, FACILITATOR_BOOT);
        } catch (RuntimeException | AssertionError e) {
            if (failure != null) e.addSuppressed(failure);
            throw e;
        }
        if (failure != null) throw failure;
    }
}
