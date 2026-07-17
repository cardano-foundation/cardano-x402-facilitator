package org.cardanofoundation.x402.facilitator.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mirrors the TS stack's tolerance (NON_NULL out, unknown fields ignored in) and
 * bounds the JSON parser (spec section 13): a hostile body can otherwise force
 * deep nesting or gigantic scalars before the DTO is ever seen. These caps sit
 * under the {@link RequestSizeFilter} byte bound as defence in depth.
 */
@Configuration
public class JacksonConfig {

    // A payment envelope is shallow (a handful of nested objects) with only
    // base64/hex/number scalars — these ceilings are far above anything valid.
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int MAX_STRING_LEN = 200_000; // base64 tx well under this
    private static final int MAX_NUMBER_LEN = 1_000;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer protocolJsonCustomizer() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LEN)
                .maxNumberLength(MAX_NUMBER_LEN)
                .build();
        JsonFactory factory = JsonFactory.builder().streamReadConstraints(constraints).build();
        return builder -> builder
                .factory(factory)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
