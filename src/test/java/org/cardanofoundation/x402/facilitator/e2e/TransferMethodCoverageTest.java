package org.cardanofoundation.x402.facilitator.e2e;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stackless gate: every {@code assetTransferMethods} entry the facilitator advertises has, in
 * {@code e2e/coverage/transfer-methods.yml}, at least one positive Java e2e test and at least one
 * TypeScript SDK test. Reads X402FacilitatorRegistry.java as SOURCE TEXT, so a newly added
 * method fails this test until someone decides how the suite covers it.
 *
 * <p>The checker is a set of pure functions with self-tests of their own: without them, this
 * gate would only ever be shown red because its manifest file was missing.
 */
class TransferMethodCoverageTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path REGISTRY = ROOT.resolve(
            "src/main/java/org/cardanofoundation/x402/facilitator/service/registry/X402FacilitatorRegistry.java");
    private static final Path MANIFEST = ROOT.resolve("e2e/coverage/transfer-methods.yml");
    private static final Path E2E_SOURCES = ROOT.resolve("src/e2eTest/java");
    private static final Path TS_TESTS = ROOT.resolve("e2e/ts/test");

    private static final Pattern METHODS = Pattern.compile(
            "\"assetTransferMethods\"\\s*,\\s*List\\.of\\(([^)]*)\\)");

    /** One manifest entry: the positive Java tests and the TS test names covering a method. */
    record Coverage(List<String> java, List<String> ts) {
    }

    // ---- the gate, against the real files ----

    @Test
    void everyAdvertisedTransferMethodIsCoveredByAJavaAndATsTest() throws IOException {
        List<String> methods = parseMethods(Files.readString(REGISTRY));
        assertThat(methods).as("methods parsed from the registry").isNotEmpty();
        assertThat(MANIFEST).as("the transfer-method coverage manifest").exists();

        Map<String, String> javaSources = e2eSources();
        String tsSources = tsSources();
        List<String> problems = check(methods, loadManifest(Files.readString(MANIFEST)),
                ref -> javaTestExists(javaSources, ref), name -> tsTestExists(tsSources, name));
        assertThat(problems).isEmpty();
    }

    // ---- self-tests of the checker ----

    @Test
    void parsesTheMethodListFromRegistrySource() {
        String source = "extra.put(\"assetTransferMethods\", List.of(\"default\", \"masumi\",\n \"script\"));";
        assertThat(parseMethods(source)).containsExactly("default", "masumi", "script");
        assertThat(parseMethods("nothing here")).isEmpty();
    }

    @Test
    void aFullyCoveredManifestHasNoProblems() {
        assertThat(check(List.of("a", "b"), manifest("a", "A#t", "ts a", "b", "B#t", "ts b"), ok -> true, ok -> true))
                .isEmpty();
    }

    @Test
    void aMethodMissingFromTheManifestIsReported() {
        List<String> problems = check(List.of("a", "b"), manifest("a", "A#t", "ts a"), ok -> true, ok -> true);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("b").contains("not in the manifest");
    }

    @Test
    void aStaleManifestEntryIsReported() {
        List<String> problems = check(List.of("a"), manifest("a", "A#t", "ts a", "gone", "G#t", "ts g"),
                ok -> true, ok -> true);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("gone").contains("stale");
    }

    @Test
    void aTsNameThatDoesNotExistIsReported() {
        List<String> problems = check(List.of("a"), manifest("a", "A#t", "ts a"), ok -> true, name -> false);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("a").contains("ts a").contains("TS");
    }

    @Test
    void aJavaTestThatDoesNotExistIsReported() {
        List<String> problems = check(List.of("a"), manifest("a", "A#t", "ts a"), ref -> false, ok -> true);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("a").contains("A#t").contains("Java");
    }

    @Test
    void anEntryWithNoJavaOrNoTsTestIsReported() {
        Map<String, Coverage> noJava = Map.of("a", new Coverage(List.of(), List.of("ts a")));
        Map<String, Coverage> noTs = Map.of("a", new Coverage(List.of("A#t"), List.of()));
        assertThat(check(List.of("a"), noJava, ok -> true, ok -> true)).hasSize(1);
        assertThat(check(List.of("a"), noTs, ok -> true, ok -> true)).hasSize(1);
    }

    @Test
    void theTsResolverFindsOnlyNamesDeclaredAsTests() {
        String source = "describe(\"suite\", () => {\n  test(\"default method, ADA\", async () => {});\n"
                + "  // test(\"commented out\", () => {});\n  const other = \"just a string\";\n});";
        assertThat(tsTestExists(source, "default method, ADA")).isTrue();
        assertThat(tsTestExists(source, "script method")).isFalse();
        assertThat(tsTestExists(source, "default method")).isFalse();
        assertThat(tsTestExists(source, "commented out")).isFalse();
        assertThat(tsTestExists(source, "just a string")).isFalse();
        assertThat(tsTestExists(source, "")).isFalse();
        assertThat(tsTestExists(source, "test(")).isFalse();
    }

    @Test
    void theJavaResolverFindsOnlyRealMethodsAndIgnoresComments() {
        Map<String, String> sources = Map.of("A", "class A { // void ghost() {}\n void real() { } }");
        assertThat(javaTestExists(sources, "A#real")).isTrue();
        assertThat(javaTestExists(sources, "A#ghost")).isFalse();
        assertThat(javaTestExists(sources, "B#real")).isFalse();
        assertThat(javaTestExists(sources, "A")).isFalse();
    }

    // ---- the checker (pure) ----

    static List<String> parseMethods(String registrySource) {
        Matcher list = METHODS.matcher(withoutComments(registrySource));
        List<String> methods = new ArrayList<>();
        if (list.find()) {
            Matcher literal = Pattern.compile("\"([^\"]+)\"").matcher(list.group(1));
            while (literal.find()) methods.add(literal.group(1));
        }
        return methods;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Coverage> loadManifest(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<String, Object> raw = new Yaml(new SafeConstructor(options)).load(yaml);
        Map<String, Coverage> manifest = new LinkedHashMap<>();
        if (raw == null) return manifest;
        raw.forEach((method, value) -> {
            Map<String, Object> fields = value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            manifest.put(method, new Coverage(strings(fields.get("java")), strings(fields.get("ts"))));
        });
        return manifest;
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** Every problem between the advertised methods and the manifest; empty means covered. */
    static List<String> check(List<String> methods, Map<String, Coverage> manifest,
                              Predicate<String> javaTestExists, Predicate<String> tsTestExists) {
        List<String> problems = new ArrayList<>();
        for (String method : methods) {
            Coverage coverage = manifest.get(method);
            if (coverage == null) {
                problems.add(method + ": advertised by the registry but not in the manifest");
                continue;
            }
            if (coverage.java().isEmpty()) problems.add(method + ": no Java e2e test listed");
            if (coverage.ts().isEmpty()) problems.add(method + ": no TS test listed");
            for (String ref : coverage.java())
                if (!javaTestExists.test(ref)) problems.add(method + ": Java test not found: " + ref);
            for (String name : coverage.ts())
                if (!tsTestExists.test(name)) problems.add(method + ": TS test not found: \"" + name + "\"");
        }
        for (String listed : manifest.keySet())
            if (!methods.contains(listed)) problems.add(listed + ": stale manifest entry, the registry does not advertise it");
        return problems;
    }

    /** {@code Class#method}: the class exists in the e2e sources and declares {@code void method(}. */
    static boolean javaTestExists(Map<String, String> sourcesByClass, String ref) {
        String[] parts = ref.split("#", 2);
        String source = sourcesByClass.get(parts[0]);
        if (source == null || parts.length != 2) return false;
        return Pattern.compile("\\bvoid\\s+" + Pattern.quote(parts[1]) + "\\s*\\(")
                .matcher(withoutComments(source)).find();
    }

    /** Block and line comments stripped; crude on purpose, it can only make the gate stricter. */
    static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    static boolean tsTestExists(String tsSources, String name) {
        return Pattern.compile("\\btest\\(\\s*\"" + Pattern.quote(name) + "\"").matcher(withoutComments(tsSources)).find();
    }

    private static Map<String, String> e2eSources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(E2E_SOURCES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList())
                sources.put(p.getFileName().toString().replace(".java", ""), Files.readString(p));
        }
        return sources;
    }

    private static String tsSources() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(TS_TESTS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".mjs")).toList())
                all.append(Files.readString(p)).append('\n');
        }
        return all.toString();
    }

    private static Map<String, Coverage> manifest(String... triples) {
        Map<String, Coverage> manifest = new LinkedHashMap<>();
        for (int i = 0; i < triples.length; i += 3)
            manifest.put(triples[i], new Coverage(List.of(triples[i + 1]), List.of(triples[i + 2])));
        return manifest;
    }
}
