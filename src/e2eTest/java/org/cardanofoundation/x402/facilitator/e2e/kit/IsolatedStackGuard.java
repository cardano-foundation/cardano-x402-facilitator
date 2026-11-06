package org.cardanofoundation.x402.facilitator.e2e.kit;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.platform.commons.support.AnnotationSupport;

import java.time.Duration;
import java.util.Optional;

/**
 * Registered through ServiceLoader (junit.jupiter.extensions.autodetection.enabled),
 * so it sees EVERY test class and acts only after an {@code @Isolated} one. An
 * isolated class is a class that breaks the stack on purpose (a paused shim, a
 * stopped chain); JUnit runs this AfterAll callback while the class still holds its
 * isolation lock, so the wait below finishes before any other class starts.
 *
 * <p>It first DELETEs every shim fault and the shim profile, then waits until BOTH facilitators report readiness UP, so the next classes never
 * run against a half-recovered stack. That covers a class that itself waits for only
 * the default instance, without editing it.
 */
public final class IsolatedStackGuard implements AfterAllCallback {

    private static final Duration RECOVERY_DEADLINE = Duration.ofSeconds(180);

    @Override
    public void afterAll(ExtensionContext context) {
        if (!AnnotationSupport.isAnnotated(context.getRequiredTestClass(), Isolated.class)) return;
        // No shim fault or profile may leak into a later class. Done first: a rate-limited or
        // faulted shim would also keep the readiness waits below from succeeding.
        RuntimeException resetFailure = null;
        try {
            ShimClient.fromEnv().reset();
        } catch (RuntimeException e) {
            resetFailure = e; // still wait for readiness, then report it
        }
        try {
            awaitReady("default facilitator", FacilitatorClient.defaultInstance());
            awaitReady("mempool facilitator", FacilitatorClient.mempoolInstance());
        } catch (RuntimeException | AssertionError e) {
            if (resetFailure != null) e.addSuppressed(resetFailure);
            throw e;
        }
        if (resetFailure != null) throw resetFailure;
    }

    private static void awaitReady(String what, FacilitatorClient instance) {
        Poll.until(what + " readiness UP after an isolated class", RECOVERY_DEADLINE, () -> {
            FacilitatorClient.Response r = instance.get("/actuator/health/readiness");
            return r.status() == 200 && "UP".equals(r.json().path("status").asText()) ? Optional.of(true) : Optional.empty();
        });
    }
}
