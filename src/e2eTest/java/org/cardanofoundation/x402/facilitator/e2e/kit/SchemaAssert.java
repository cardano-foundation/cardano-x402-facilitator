package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Validates response bodies against the JSON schemas in
 * {@code src/e2eTest/resources/schema/} — the wire contract, independent of
 * main's Java records.
 */
public final class SchemaAssert {

    public static final String VERIFY = "VerifyResponse";
    public static final String SETTLE = "SettleResponse";
    public static final String SUPPORTED = "SupportedResponse";
    public static final String HEALTH = "Health";
    public static final String ERROR = "ErrorResponse";

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private SchemaAssert() {
    }

    /** @throws AssertionError listing every violation, with the offending body. */
    public static JsonNode assertMatches(String schema, JsonNode body) {
        Set<ValidationMessage> errors = CACHE.computeIfAbsent(schema, SchemaAssert::load).validate(body);
        if (!errors.isEmpty()) {
            throw new AssertionError("body violates " + schema + " schema:\n  "
                    + errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("\n  "))
                    + "\nbody: " + body);
        }
        return body;
    }

    public static JsonNode assertMatches(String schema, FacilitatorClient.Response response) {
        return assertMatches(schema, response.json());
    }

    private static JsonSchema load(String name) {
        String path = "/schema/" + name + ".json";
        try (InputStream in = SchemaAssert.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("no schema " + path);
            return FACTORY.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
