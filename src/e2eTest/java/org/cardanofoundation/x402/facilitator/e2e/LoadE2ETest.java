package org.cardanofoundation.x402.facilitator.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.log4j.Log4j2;
import org.cardanofoundation.x402.facilitator.e2e.kit.Case;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.Load;
import org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.Poll;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Stack;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settlement invariants under concurrency, and a latency report, at the scale of
 * {@code E2E_LOAD_SCALE} ({@code pr} default, {@code nightly}; see {@link Load}).
 *
 * <p>Phases: (1) pre-generation: every payment of the settle mix is funded and built, outside any
 * timing; (2) settle mix: weighted SETTLED {@link Case} rows at a fixed arrival rate, 5 % of the
 * slots re-sending an already settled payment; (3) verify burst against a slow, rate-limited
 * shim; (4) one reconciler sweep, then the database check.
 *
 * <p>Only the invariants fail the test: every {@code success: true} hash is on chain; the shim saw
 * at most one submit per hash (duplicates included); every one of THIS test's hashes that is on
 * chain is CONFIRMED after the sweep. Latency, answer codes, readiness DOWN samples and rows left
 * unresolved for hashes that are not on chain are reported in
 * {@code e2e/.out/load/summary.json} / {@code summary.md}, never failed.
 *
 * <p>{@code @Isolated}: the shim profile is path-wide, and the run should not share the facilitator
 * with other classes. Expected answer codes are not asserted, so none are written here.
 */
@Log4j2
@Isolated
@StackRequired
class LoadE2ETest {

    private static final Path OUT = Path.of("e2e/.out/load");
    private static final long WALLET_ADA = 40;
    private static final int BUILD_THREADS = 8;
    private static final int DUPLICATE_EVERY = 20;
    /** The reconciler sweeps about every 30 s; one full sweep plus margin. */
    private static final Duration SWEEP = Duration.ofSeconds(35);
    private static final Duration CONFIRM_DEADLINE = Duration.ofSeconds(90);
    private static final Duration ON_CHAIN_DEADLINE = Duration.ofSeconds(120);
    private static final long LONG_TTL_SLOTS = 550;

    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final Load.Recorder recorder = new Load.Recorder();
    private volatile String phase = "pregeneration";
    private final AtomicLong oldestAgeAtSendMs = new AtomicLong();
    private final AtomicInteger expired = new AtomicInteger();

    /** A weighted SETTLED row of the mix. */
    private record Row(Case c, int weight) {
    }

    private record Item(String row, Payment payment, long builtAtNanos) {
    }

    @Test
    void settlementInvariantsHoldUnderConcurrencyAndLatencyIsReported() throws Exception {
        long started = System.nanoTime();
        Load.Scale scale = Load.scale();
        DevnetKit kit = DevnetKit.fromEnv();
        PaymentFactory payments = new PaymentFactory(kit);
        FacilitatorClient facilitator = FacilitatorClient.defaultInstance();
        FacilitatorClient mempool = FacilitatorClient.mempoolInstance();
        ShimClient shim = ShimClient.fromEnv();
        shim.reset();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scale", scale.name());
        summary.put("shimProfile", profileDescription());
        Thread sampler = startReadinessSampler(facilitator);
        List<Item> items = List.of();
        try {
            // ---- 1. pre-generation: nothing below is built inside a measured window ----
            ShapeMatrixE2ETest.stack(); // the shape rows' bases read statics that class sets up
            List<Row> rows = rows(kit, payments);
            int[] counts = allocate(rows, scale.payments());
            List<Wallet> wallets = Funding.wallets(scale.payments() + 1, WALLET_ADA);
            Wallet burstPayer = wallets.get(scale.payments());
            summary.put("fundingFanOut", Funding.largestFanOut());
            long pregenStart = System.nanoTime();
            items = pregenerate(rows, counts, wallets);
            long pregenMs = millisSince(pregenStart);
            // The burst payment is built here too, so nothing is built after a phase has started.
            Payment valid = payments.from(burstPayer).payTo(Case.standardReceiver()).lovelace(2_000_000)
                    .ttlSlots(LONG_TTL_SLOTS).build();
            summary.put("mix", mixDescription(rows, counts));
            summary.put("pregenerationSeconds", pregenMs / 1000);
            log.info("load: {} payments pre-generated in {} ms", items.size(), pregenMs);

            // ---- 2. settle mix ----
            phase = "mix";
            long mixStart = System.nanoTime();
            Set<String> duplicateHashes = ConcurrentHashMap.newKeySet();
            int duplicates = runMix(items, scale, facilitator, duplicateHashes);
            long mixMs = millisSince(mixStart);
            summary.put("mixSeconds", mixMs / 1000);
            summary.put("arrivalsPerSecond", scale.arrivalsPerSecond());
            if (duplicates == 0 && !settledPayments.isEmpty()) {
                // No duplicate slot fired (or nothing was settled yet when it did): send one now.
                duplicates += sendForcedDuplicate(facilitator, duplicateHashes);
            }
            summary.put("duplicateSlots", duplicates);
            summary.put("oldestPaymentAgeAtSendSeconds", oldestAgeAtSendMs.get() / 1000);
            summary.put("successfulOriginalSettles", settledPayments.size());
            if (settledPayments.isEmpty()) {
                violations.add("HARNESS: no original settle succeeded, so the run certifies nothing");
            }
            if (duplicates == 0) {
                violations.add("HARNESS: no duplicate was sent, so the no-second-broadcast check for duplicates is unproven");
            }
            checkSuccessesAreOnChain(kit);

            // ---- 3. verify burst, then the profile is gone ----
            phase = "burst";
            long burstStart = System.nanoTime();
            try {
                shim.setProfile(Load.PROFILE_LATENCY_MS, Load.PROFILE_RATE_PER_SEC, Load.PROFILE_BURST);
                verifyBurst(scale, facilitator, valid);
            } finally {
                shim.reset();
            }
            summary.put("burstSeconds", millisSince(burstStart) / 1000);
            phase = "recovery";
            awaitReady("default facilitator", facilitator);
            awaitReady("mempool facilitator", mempool);

            // ---- 4. one reconciler sweep, then the invariants over the whole run ----
            phase = "sweep";
            long sweepStart = System.nanoTime();
            // Not a blind wait for a state: the reconciler's schedule is the thing being given time.
            Thread.sleep(SWEEP.toMillis());
            checkSubmitCounts(shim, items, duplicateHashes);
            Map<String, Object> database = checkDatabase(kit, items);
            summary.put("settlementRows", database);
            summary.put("sweepSeconds", millisSince(sweepStart) / 1000);
        } finally {
            sampler.interrupt();
            sampler.join(10_000);
            try {
                shim.reset();
            } catch (RuntimeException e) {
                violations.add("could not clear the shim profile: " + e);
            }
            // Checked last, so expiries in the verify burst count as much as expiries in the mix.
            summary.put("ttlExpiredAnswers", expired.get());
            if (expired.get() > 0) {
                violations.add("HARNESS: " + expired.get() + " answer(s) were ttl_expired; the run is too slow for the payments' validity");
            }
            summary.put("totalSeconds", millisSince(started) / 1000);
            summary.put("readiness", recorder.readiness());
            summary.put("violations", new ArrayList<>(violations));
            summary.put("latency", recorder.series());
            try {
                Load.writeSummary(OUT, summary);
                log.info("load: summary written to {}", OUT.toAbsolutePath());
            } catch (RuntimeException e) {
                log.warn("load: could not write the summary (the report never fails the test): {}", e.toString());
            }
        }
        assertThat(violations).as("settlement invariants (see %s/summary.md)", OUT).isEmpty();
    }

    // ---- pre-generation ----

    private List<Row> rows(DevnetKit kit, PaymentFactory payments) {
        MasumiLock.Deployment deployment = MasumiLock.deployments().allowlisted();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Case.expect(Case.SETTLED, "default ADA transfer")
                .base(w -> payments.from(w).payTo(Case.standardReceiver()).lovelace(2_000_000)
                        .ttlSlots(LONG_TTL_SLOTS).build()), 45));
        rows.add(new Row(Case.expect(Case.SETTLED, "default native-token transfer")
                .base(w -> {
                    String asset = kit.mintToken(w, "LOADTOKEN", 1_000);
                    return payments.from(w).payTo(Case.standardReceiver()).token(asset, 100)
                            .ttlSlots(LONG_TTL_SLOTS).build();
                }), 15));
        rows.add(new Row(Case.expect(Case.SETTLED, "Masumi ADA lock")
                .base(w -> masumi(kit, payments, deployment, false, w)), 10));
        rows.add(new Row(Case.expect(Case.SETTLED, "Masumi token lock")
                .base(w -> masumi(kit, payments, deployment, true, w)), 5));
        // The shape rows: every SETTLED row of the shape matrix, 5 each. Their bases create chain state
        // (a token mint, a second UTxO, an enterprise top-up) per call, each with the row's own wallet.
        ShapeMatrixE2ETest.cases().filter(Case::expectsSettled).forEach(c -> rows.add(new Row(c, 5)));
        return rows;
    }

    /** A Masumi lock under terms that are unique per lock: terms are claimable once (terms_digest). */
    private static Payment masumi(DevnetKit kit, PaymentFactory payments, MasumiLock.Deployment deployment,
                                  boolean token, Wallet payer) {
        MasumiLock lock = new MasumiLock(kit, payments, deployment);
        if (token) lock.token();
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String sellerNonce = HexFormat.of().formatHex(nonce);
        lock.preSign = extra -> MasumiLock.terms(extra).put("sellerNonce", sellerNonce);
        return lock.build(payer).payment();
    }

    /** Largest-remainder split of {@code total} payments over the row weights. */
    private static int[] allocate(List<Row> rows, int total) {
        int weights = rows.stream().mapToInt(Row::weight).sum();
        int[] counts = new int[rows.size()];
        double[] remainder = new double[rows.size()];
        int assigned = 0;
        for (int i = 0; i < rows.size(); i++) {
            double exact = (double) total * rows.get(i).weight() / weights;
            counts[i] = (int) Math.floor(exact);
            remainder[i] = exact - counts[i];
            assigned += counts[i];
        }
        while (assigned < total) {
            int best = 0;
            for (int i = 1; i < rows.size(); i++) if (remainder[i] > remainder[best]) best = i;
            counts[best]++;
            remainder[best] = -1;
            assigned++;
        }
        return counts;
    }

    private static Map<String, Object> mixDescription(List<Row> rows, int[] counts) {
        Map<String, Object> mix = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("weight", rows.get(i).weight());
            row.put("payments", counts[i]);
            mix.put(rows.get(i).c().name(), row);
        }
        return mix;
    }

    /**
     * Builds every payment on a small pool. Build tasks are submitted in a fixed shuffled order and the
     * result is kept in completion order: the earliest-built payment is also the earliest sent, so no
     * payment is older than the pre-generation plus the arrival window when it arrives (the default
     * ttl is 300 slots).
     */
    private List<Item> pregenerate(List<Row> rows, int[] counts, List<Wallet> wallets) throws Exception {
        List<Integer> plan = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) for (int n = 0; n < counts[i]; n++) plan.add(i);
        Collections.shuffle(plan, new Random(42));

        ExecutorService pool = Executors.newFixedThreadPool(BUILD_THREADS);
        try {
            CompletionService<Item> done = new ExecutorCompletionService<>(pool);
            for (int slot = 0; slot < plan.size(); slot++) {
                Row row = rows.get(plan.get(slot));
                Wallet payer = wallets.get(slot);
                done.submit(() -> {
                    Payment control = row.c().base().apply(payer);
                    Payment built = row.c().defect().apply(control, payer);
                    return new Item(row.c().name(), built, System.nanoTime());
                });
            }
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < plan.size(); i++) {
                try {
                    items.add(done.take().get());
                } catch (ExecutionException e) {
                    throw new AssertionError("pre-generation failed (a harness error, not a facilitator result)", e.getCause());
                }
            }
            return items;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- settle mix ----

    private final Map<String, Payment> settledPayments = new ConcurrentHashMap<>();

    /** @return the number of slots that re-sent an already settled payment. */
    private int runMix(List<Item> items, Load.Scale scale, FacilitatorClient facilitator, Set<String> duplicateHashes)
            throws Exception {
        ExecutorService senders = Executors.newCachedThreadPool();
        List<Future<?>> inFlight = new ArrayList<>();
        Random random = new Random(7);
        long intervalNanos = (long) (1_000_000_000L / scale.arrivalsPerSecond());
        long t0 = System.nanoTime();
        int next = 0;
        int duplicates = 0;
        try {
            for (int slot = 0; next < items.size(); slot++) {
                long due = t0 + slot * intervalNanos;
                long wait = due - System.nanoTime();
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                boolean duplicateSlot = slot % DUPLICATE_EVERY == DUPLICATE_EVERY - 1 && !settledPayments.isEmpty();
                if (duplicateSlot) {
                    List<Payment> pool = new ArrayList<>(settledPayments.values());
                    Payment original = pool.get(random.nextInt(pool.size()));
                    duplicateHashes.add(original.txHash());
                    duplicates++;
                    inFlight.add(senders.submit(() -> settle("settle-duplicate", facilitator, original, 0)));
                } else {
                    Item item = items.get(next++);
                    inFlight.add(senders.submit(() -> settle("settle", facilitator, item.payment(), item.builtAtNanos())));
                }
            }
            for (Future<?> f : inFlight) f.get();
        } finally {
            senders.shutdownNow();
        }
        return duplicates;
    }

    /** @return 1 (one duplicate was sent). */
    private int sendForcedDuplicate(FacilitatorClient facilitator, Set<String> duplicateHashes) {
        Payment original = settledPayments.values().iterator().next();
        duplicateHashes.add(original.txHash());
        settle("settle-duplicate", facilitator, original, 0);
        return 1;
    }

    /** One /settle. Never throws: a transport failure is a code in the report. */
    private void settle(String series, FacilitatorClient facilitator, Payment payment, long builtAtNanos) {
        boolean original = builtAtNanos != 0;
        long start = System.nanoTime();
        if (original) oldestAgeAtSendMs.accumulateAndGet((start - builtAtNanos) / 1_000_000, Math::max);
        String code;
        try {
            FacilitatorClient.Response response = facilitator.settle(payment.body());
            long ms = millisSince(start);
            code = settleCode(response, payment);
            recorder.record(series, code, ms);
            if ("success".equals(code) && original) settledPayments.put(payment.txHash(), payment);
        } catch (RuntimeException e) {
            recorder.record(series, "io_error", millisSince(start));
            log.warn("settle of {} failed in transport: {}", payment.txHash(), e.toString());
        }
    }

    private String settleCode(FacilitatorClient.Response response, Payment payment) {
        if (response.status() != 200) return "http_" + response.status();
        JsonNode body;
        try {
            body = FacilitatorClient.JSON.readTree(response.body());
        } catch (Exception e) {
            return "malformed_body";
        }
        if (body.path("success").asBoolean()) {
            String hash = body.path("transaction").asText();
            if (!hash.equals(payment.txHash())) {
                violations.add("settle answered success for " + payment.txHash() + " with transaction " + hash);
            }
            successHashes.add(payment.txHash());
            return "success";
        }
        String reason = body.path("errorReason").asText();
        if (reason.endsWith("ttl_expired")) expired.incrementAndGet();
        return reason.isEmpty() ? "failure_without_reason" : reason;
    }

    private final Set<String> successHashes = ConcurrentHashMap.newKeySet();

    // ---- verify burst ----

    private void verifyBurst(Load.Scale scale, FacilitatorClient facilitator, Payment valid) throws Exception {
        String request = FacilitatorClient.toJson(valid.body());
        for (int level : scale.levels()) {
            for (int round = 0; round < scale.rounds(); round++) {
                ExecutorService workers = Executors.newFixedThreadPool(level);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int w = 0; w < level; w++) {
                    futures.add(workers.submit(() -> {
                        go.await();
                        for (int r = 0; r < scale.requestsPerWorker(); r++) verifyOnce("verify@L" + level, facilitator, request);
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : futures) f.get();
                workers.shutdown();
            }
        }
    }

    private void verifyOnce(String series, FacilitatorClient facilitator, String request) {
        long start = System.nanoTime();
        try {
            FacilitatorClient.Response response = facilitator.post("/verify", request);
            long ms = millisSince(start);
            String code;
            if (response.status() != 200) {
                code = "http_" + response.status();
            } else {
                JsonNode body = FacilitatorClient.JSON.readTree(response.body());
                if (body.path("invalidReason").asText().endsWith("ttl_expired")) expired.incrementAndGet();
                code = body.path("isValid").asBoolean() ? "valid"
                        : (body.path("invalidReason").asText().isEmpty() ? "invalid_without_reason" : body.path("invalidReason").asText());
            }
            recorder.record(series, code, ms);
        } catch (Exception e) {
            recorder.record(series, "io_error", millisSince(start));
        }
    }

    // ---- invariants ----

    /** Invariant 1: every success:true hash is in a block, seen by Yano itself (one shared deadline). */
    private void checkSuccessesAreOnChain(DevnetKit kit) {
        Set<String> missing = new LinkedHashSet<>(successHashes);
        try {
            Poll.until("every success:true hash on chain (Yano direct)", ON_CHAIN_DEADLINE, () -> {
                missing.removeIf(hash -> kit.isOnChain(hash));
                return missing.isEmpty() ? Optional.of(true) : Optional.empty();
            });
        } catch (AssertionError e) {
            for (String hash : missing) violations.add("success:true but not on chain (Yano direct): " + hash);
        }
    }

    /**
     * Invariants 2 and 3: every success:true hash was broadcast exactly once (which also proves the shim
     * counter sees the facilitator's own submits), no hash more than once, duplicates included.
     */
    private void checkSubmitCounts(ShimClient shim, List<Item> items, Set<String> duplicateHashes) {
        for (Item item : items) {
            String hash = item.payment().txHash();
            int count = shim.submitCount(item.payment().txBytes());
            String kind = duplicateHashes.contains(hash) ? " (a duplicate was sent for it)" : "";
            if (count > 1) {
                violations.add("hash broadcast " + count + " times (>1)" + kind + ": " + hash + " [" + item.row() + "]");
            } else if (count != 1 && successHashes.contains(hash)) {
                violations.add("success:true but the shim counted " + count + " submits (expected exactly 1)" + kind
                        + ": " + hash + " [" + item.row() + "]");
            }
        }
    }

    /**
     * Invariant 4: after the sweep every own hash that is on chain is CONFIRMED. Rows of own hashes that are
     * NOT on chain and not CONFIRMED are only reported (an Unknown submit deliberately stays SUBMITTING).
     * A database check that never completes is itself a violation.
     */
    private Map<String, Object> checkDatabase(DevnetKit kit, List<Item> items) {
        List<String> all = items.stream().map(i -> i.payment().txHash()).toList();
        // Hashes proven on chain by invariant 1 count even if a later Yano read fails.
        Set<String> onChain = new LinkedHashSet<>(successHashes);
        for (String hash : all) {
            try {
                if (kit.isOnChain(hash)) onChain.add(hash);
            } catch (RuntimeException e) {
                log.warn("load: Yano read for {} failed: {}", hash, e.toString());
            }
        }
        AtomicReference<Map<String, String>> lastRead = new AtomicReference<>();
        List<String> notConfirmed = new ArrayList<>();
        try {
            Poll.until("every on-chain hash of this run CONFIRMED", CONFIRM_DEADLINE, () -> {
                Map<String, String> read = statuses(all);
                lastRead.set(read);
                notConfirmed.clear();
                for (String hash : onChain) if (!"CONFIRMED".equals(read.get(hash))) notConfirmed.add(hash);
                return notConfirmed.isEmpty() ? Optional.of(true) : Optional.empty();
            });
        } catch (AssertionError e) {
            if (lastRead.get() == null) {
                violations.add("database check did not complete: " + e + " (cause: "
                        + (e.getSuppressed().length > 0 ? e.getSuppressed()[0] : "none") + ")");
            } else if (notConfirmed.isEmpty()) {
                violations.add("database check did not complete: " + e);
            }
            for (String hash : notConfirmed) {
                violations.add("on chain but not CONFIRMED after the sweep: " + hash + " is "
                        + (lastRead.get() == null ? "unread" : lastRead.get().get(hash)));
            }
        }
        Map<String, String> read = lastRead.get();
        Map<String, Integer> byStatusOffChain = new TreeMap<>();
        Object confirmed = "unknown (database never read)";
        if (read != null) {
            int ok = 0;
            for (String hash : onChain) if ("CONFIRMED".equals(read.get(hash))) ok++;
            confirmed = ok;
            for (String hash : all) {
                if (onChain.contains(hash)) continue;
                String status = read.getOrDefault(hash, "NO_ROW");
                if (!"CONFIRMED".equals(status)) byStatusOffChain.merge(status, 1, Integer::sum);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ownHashes", all.size());
        report.put("onChain", onChain.size());
        report.put("onChainAndConfirmed (last successful read)", confirmed);
        report.put("notOnChainByStatus (reported, not failed)", byStatusOffChain);
        return report;
    }

    /** tx_hash to status, for these hashes, from the default facilitator's database. */
    private static Map<String, String> statuses(List<String> hashes) {
        Map<String, String> out = new TreeMap<>();
        for (int from = 0; from < hashes.size(); from += 100) {
            List<String> chunk = hashes.subList(from, Math.min(hashes.size(), from + 100));
            String in = String.join(",", chunk.stream().map(h -> "'" + h + "'").toList());
            String result = Stack.psql("postgres",
                    "select tx_hash || ',' || status from facilitator.settlement where tx_hash in (" + in + ")");
            for (String line : result.split("\n")) {
                String[] parts = line.strip().split(",");
                if (parts.length == 2) out.put(parts[0], parts[1]);
            }
        }
        return out;
    }

    // ---- helpers ----

    private Thread startReadinessSampler(FacilitatorClient facilitator) {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                boolean up;
                try {
                    FacilitatorClient.Response r = facilitator.get("/actuator/health/readiness");
                    up = r.status() == 200 && "UP".equals(r.json().path("status").asText());
                } catch (RuntimeException | AssertionError e) {
                    up = false;
                }
                recorder.readiness(phase, up);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "load-readiness-sampler");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitReady(String what, FacilitatorClient instance) {
        Poll.until(what + " readiness UP after the burst", Duration.ofSeconds(180), () -> {
            FacilitatorClient.Response r = instance.get("/actuator/health/readiness");
            return r.status() == 200 && "UP".equals(r.json().path("status").asText()) ? Optional.of(true) : Optional.empty();
        });
    }

    private static Map<String, Object> profileDescription() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("latencyMs", Load.PROFILE_LATENCY_MS);
        profile.put("ratePerSec", Load.PROFILE_RATE_PER_SEC);
        profile.put("burst", Load.PROFILE_BURST);
        profile.put("appliesTo", "verify burst only; the settle mix runs with no profile");
        return profile;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
