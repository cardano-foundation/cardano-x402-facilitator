package org.cardanofoundation.x402.facilitator.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stackless gate: every ErrorCodes constant is either asserted by a named e2e
 * test or excluded with a category and a reason, across the merged
 * {@code e2e/coverage/wp-*.yml} fragments. Reads ErrorCodes.java as SOURCE TEXT
 * (no class import), so a newly added code fails this test until someone
 * decides how the suite covers it.
 */
class ErrorCodeCoverageTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path ERROR_CODES = ROOT.resolve(
            "src/main/java/org/cardanofoundation/x402/facilitator/model/ErrorCodes.java");
    private static final Path MAIN = ROOT.resolve("src/main/java");
    private static final Path E2E_SOURCES = ROOT.resolve("src/e2eTest/java");
    private static final Path FRAGMENTS = ROOT.resolve("e2e/coverage");

    private static final Set<String> CATEGORIES = Set.of(
            "race-only", "dead", "provider-fault", "unreachable-via-http", "not-producible-on-healthy-devnet");
    private static final Pattern CONSTANT = Pattern.compile(
            "public\\s+static\\s+final\\s+String\\s+([A-Z0-9_]+)\\s*=\\s*([^;]+);");

    /** constant name → its string value, in declaration order. */
    private static Map<String, String> codes;
    /** constant name → (fragment file, entry). */
    private static Map<String, Entry> manifest;
    private static List<String> duplicates;

    record Entry(String fragment, List<String> tests, String excluded, String reason) {
    }

    @BeforeAll
    static void load() throws IOException {
        codes = parseErrorCodes(Files.readString(ERROR_CODES));
        manifest = new TreeMap<>();
        duplicates = new ArrayList<>();
        List<Path> fragments;
        try (Stream<Path> files = Files.list(FRAGMENTS)) {
            fragments = files.filter(p -> p.getFileName().toString().matches("wp-\\d+\\.yml")).sorted().toList();
        }
        assertThat(fragments).as("coverage fragments in %s", FRAGMENTS).isNotEmpty();
        for (Path fragment : fragments) {
            // A key repeated inside one fragment would otherwise silently keep the last entry.
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Map<String, Object> entries = new Yaml(new SafeConstructor(options)).load(Files.readString(fragment));
            if (entries == null) continue;
            entries.forEach((name, raw) -> {
                Entry entry = toEntry(fragment.getFileName().toString(), name, raw);
                Entry previous = manifest.put(name, entry);
                if (previous != null) duplicates.add(name + " in " + previous.fragment() + " and " + entry.fragment());
            });
        }
    }

    @Test
    void errorCodesParseToTheirStringValues() {
        // Guards the source parser itself: a known literal and a known concatenation.
        assertThat(codes).containsEntry("UNSUPPORTED_SCHEME", "unsupported_scheme")
                .containsEntry("FEE_BELOW_MINIMUM", "invalid_exact_cardano_payload_fee_below_minimum");
        assertThat(codes).hasSizeGreaterThan(40);
    }

    @Test
    void everyConstantIsMapped() {
        List<String> unmapped = codes.keySet().stream().filter(name -> !manifest.containsKey(name)).toList();
        assertThat(unmapped).as("ErrorCodes constants with no coverage entry (map them in e2e/coverage/wp-*.yml)")
                .isEmpty();
    }

    @Test
    void noEntryIsStale() {
        List<String> stale = manifest.keySet().stream().filter(name -> !codes.containsKey(name)).toList();
        assertThat(stale).as("coverage entries naming no ErrorCodes constant").isEmpty();
    }

    @Test
    void noConstantIsMappedTwice() {
        assertThat(duplicates).as("constants mapped in more than one fragment").isEmpty();
    }

    @Test
    void everyEntryIsTestedXorExcludedWithAKnownCategory() {
        List<String> malformed = new ArrayList<>();
        manifest.forEach((name, e) -> {
            boolean tested = e.tests() != null && !e.tests().isEmpty();
            boolean excluded = e.excluded() != null;
            if (tested == excluded) malformed.add(name + " (" + e.fragment() + "): needs exactly one of tests/excluded");
            if (excluded && !CATEGORIES.contains(e.excluded()))
                malformed.add(name + " (" + e.fragment() + "): unknown category " + e.excluded());
            if (excluded && (e.reason() == null || e.reason().isBlank()))
                malformed.add(name + " (" + e.fragment() + "): exclusion without a reason");
        });
        assertThat(malformed).isEmpty();
    }

    @Test
    void everyReferencedTestExistsAndAssertsTheCodesValue() throws IOException {
        Map<String, Path> sources = e2eSources();
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, Entry> m : manifest.entrySet()) {
            if (m.getValue().tests() == null || !codes.containsKey(m.getKey())) continue;
            String value = codes.get(m.getKey());
            for (String ref : m.getValue().tests()) {
                String[] parts = ref.split("#", 2);
                Path source = sources.get(parts[0]);
                if (source == null || parts.length != 2) {
                    broken.add(m.getKey() + ": no e2e test class for " + ref);
                    continue;
                }
                // Comments do not count: the code must appear in the test's actual source.
                String text = withoutComments(Files.readString(source));
                if (!Pattern.compile("\\bvoid\\s+" + Pattern.quote(parts[1]) + "\\s*\\(").matcher(text).find())
                    broken.add(m.getKey() + ": no method " + ref);
                if (!text.contains("\"" + value + "\""))
                    broken.add(m.getKey() + ": " + parts[0] + " never mentions \"" + value + "\"");
            }
        }
        assertThat(broken).isEmpty();
    }

    @Test
    void deadExclusionsAreReallyUnreferencedInMain() throws IOException {
        List<String> alive = new ArrayList<>();
        String mainText = mainSourcesExceptErrorCodes();
        manifest.forEach((name, e) -> {
            if ("dead".equals(e.excluded()) && Pattern.compile("\\bErrorCodes\\." + name + "\\b").matcher(mainText).find())
                alive.add(name + " is excluded as dead but main references it");
        });
        assertThat(alive).isEmpty();
    }

    // ---- parsing ----

    /** Resolves {@code "literal"} and {@code OTHER + "_suffix"} initializers in declaration order. */
    static Map<String, String> parseErrorCodes(String source) {
        String code = withoutComments(source);
        Map<String, String> values = new LinkedHashMap<>();
        Matcher m = CONSTANT.matcher(code);
        while (m.find()) {
            StringBuilder value = new StringBuilder();
            for (String term : m.group(2).split("\\+")) {
                String t = term.trim();
                if (t.startsWith("\"") && t.endsWith("\"")) {
                    value.append(t, 1, t.length() - 1);
                } else if (values.containsKey(t)) {
                    value.append(values.get(t));
                } else {
                    throw new IllegalStateException("cannot resolve " + t + " in " + m.group(1));
                }
            }
            values.put(m.group(1), value.toString());
        }
        return values;
    }

    /**
     * Strips block and line comments. Crude ({@code //} inside a string literal
     * also starts a "comment"), which can only make the gate stricter, never looser.
     */
    static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @SuppressWarnings("unchecked")
    private static Entry toEntry(String fragment, String name, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return new Entry(fragment, null, null, null);
        Map<String, Object> fields = (Map<String, Object>) map;
        Object tests = fields.get("tests");
        return new Entry(fragment,
                tests instanceof List<?> list ? list.stream().map(String::valueOf).toList() : null,
                fields.get("excluded") == null ? null : String.valueOf(fields.get("excluded")),
                fields.get("reason") == null ? null : String.valueOf(fields.get("reason")));
    }

    private static Map<String, Path> e2eSources() throws IOException {
        Map<String, Path> sources = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(E2E_SOURCES)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> sources.put(p.getFileName().toString().replace(".java", ""), p));
        }
        return sources;
    }

    private static String mainSourcesExceptErrorCodes() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(p -> p.toString().endsWith(".java") && !p.equals(ERROR_CODES)).forEach(p -> {
                try {
                    all.append(Files.readString(p)).append('\n');
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return all.toString();
    }
}
