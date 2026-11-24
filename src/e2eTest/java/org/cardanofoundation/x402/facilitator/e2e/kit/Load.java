package org.cardanofoundation.x402.facilitator.e2e.kit;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The load test's scale, its shim profile and its report. Scales are Java constants, not files:
 * {@code E2E_LOAD_SCALE} ({@code pr} by default, {@code nightly}) picks one.
 *
 * <p>{@link Recorder} collects, per series ({@code verify@L8}, {@code settle}, ...), the latency of
 * every request and a count per answer code. The report is only a report: nothing in here fails a test.
 */
@Log4j2
public final class Load {

    public static final String SCALE_ENV = "E2E_LOAD_SCALE";

    /** The verify burst's shim profile (the same at every scale). */
    public static final int PROFILE_LATENCY_MS = 150;
    public static final double PROFILE_RATE_PER_SEC = 10;
    public static final int PROFILE_BURST = 500;

    private Load() {
    }

    /**
     * @param levels             concurrency levels of the verify burst.
     * @param rounds             rounds per level.
     * @param requestsPerWorker  verify requests each concurrent worker sends per round.
     * @param payments           settle-mix payments (duplicates come on top).
     * @param arrivalsPerSecond  fixed arrival rate of the settle mix.
     */
    public record Scale(String name, int[] levels, int rounds, int requestsPerWorker, int payments, double arrivalsPerSecond) {
    }

    public static final Scale PR = new Scale("pr", new int[]{1, 8, 32}, 1, 4, 20, 2.0);
    /**
     * Several hundred payments at a rate high enough that the last payment is sent well inside the
     * 300-slot validity of the Masumi and shape rows' payments, which are all built with the default
     * ttl before the mix starts (measured: 2.0/s sends the oldest payment at about 167 s of age).
     */
    public static final Scale NIGHTLY = new Scale("nightly", new int[]{1, 4, 8, 16, 32, 64, 128}, 3, 8, 300, 2.0);

    public static Scale scale() {
        String value = System.getenv(SCALE_ENV);
        if (value == null || value.isBlank() || "pr".equalsIgnoreCase(value.strip())) return PR;
        if ("nightly".equalsIgnoreCase(value.strip())) return NIGHTLY;
        throw new IllegalStateException(SCALE_ENV + " must be 'pr' or 'nightly', not '" + value + "'");
    }

    /** Nearest-rank percentile of a sorted list (milliseconds). */
    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.min(sorted.size(), Math.max(1, rank)) - 1);
    }

    /** Thread-safe latency and answer-code collector. */
    public static final class Recorder {
        private final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
        private final Map<String, Map<String, AtomicInteger>> codes = new ConcurrentHashMap<>();
        private final AtomicInteger readinessSamples = new AtomicInteger();
        private final AtomicInteger readinessDown = new AtomicInteger();
        private final Map<String, AtomicInteger> readinessDownByPhase = new ConcurrentHashMap<>();

        public void record(String series, String code, long millis) {
            latencies.computeIfAbsent(series, s -> Collections.synchronizedList(new ArrayList<>())).add(millis);
            codes.computeIfAbsent(series, s -> new ConcurrentHashMap<>())
                    .computeIfAbsent(code, c -> new AtomicInteger()).incrementAndGet();
        }

        public void readiness(String phase, boolean up) {
            readinessSamples.incrementAndGet();
            if (!up) {
                readinessDown.incrementAndGet();
                readinessDownByPhase.computeIfAbsent(phase, p -> new AtomicInteger()).incrementAndGet();
            }
        }

        /** One entry per series, in series-name order: count, p50/p95/p99/max in ms, answer codes. */
        public Map<String, Object> series() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String name : new TreeMap<>(latencies).keySet()) {
                List<Long> sorted;
                synchronized (latencies.get(name)) {
                    sorted = new ArrayList<>(latencies.get(name));
                }
                Collections.sort(sorted);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("count", sorted.size());
                entry.put("p50Ms", percentile(sorted, 50));
                entry.put("p95Ms", percentile(sorted, 95));
                entry.put("p99Ms", percentile(sorted, 99));
                entry.put("maxMs", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
                Map<String, Integer> byCode = new TreeMap<>();
                codes.getOrDefault(name, Map.of()).forEach((code, n) -> byCode.put(code, n.get()));
                entry.put("codes", byCode);
                out.put(name, entry);
            }
            return out;
        }

        public Map<String, Object> readiness() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("samples", readinessSamples.get());
            out.put("downSamples", readinessDown.get());
            Map<String, Integer> byPhase = new TreeMap<>();
            readinessDownByPhase.forEach((phase, n) -> byPhase.put(phase, n.get()));
            out.put("downByPhase", byPhase);
            return out;
        }
    }

    /** Writes {@code summary.json} and {@code summary.md} into {@code dir}. */
    @SuppressWarnings("unchecked")
    public static void writeSummary(Path dir, Map<String, Object> summary) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("summary.json"),
                    FacilitatorClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n");
            Files.writeString(dir.resolve("summary.md"), markdown(summary));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the load summary to " + dir, e);
        }
    }

    @SuppressWarnings("unchecked")
    static String markdown(Map<String, Object> summary) {
        StringBuilder md = new StringBuilder("# Load summary\n\n");
        for (Map.Entry<String, Object> section : summary.entrySet()) {
            if (section.getKey().equals("latency")) continue;
            md.append("- **").append(section.getKey()).append("**: `")
                    .append(FacilitatorClient.toJson(section.getValue())).append("`\n");
        }
        md.append("\n## Latency per endpoint and level (ms)\n\n")
                .append("| series | n | p50 | p95 | p99 | max | answers |\n|---|---|---|---|---|---|---|\n");
        Map<String, Object> latency = (Map<String, Object>) summary.getOrDefault("latency", Map.of());
        for (Map.Entry<String, Object> row : latency.entrySet()) {
            Map<String, Object> e = (Map<String, Object>) row.getValue();
            md.append("| ").append(row.getKey()).append(" | ").append(e.get("count"))
                    .append(" | ").append(e.get("p50Ms")).append(" | ").append(e.get("p95Ms"))
                    .append(" | ").append(e.get("p99Ms")).append(" | ").append(e.get("maxMs"))
                    .append(" | ").append(FacilitatorClient.toJson(e.get("codes"))).append(" |\n");
        }
        return md.toString();
    }
}
