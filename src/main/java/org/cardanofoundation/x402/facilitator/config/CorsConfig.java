package org.cardanofoundation.x402.facilitator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS policy. Default-deny: with no configured origins the
 * facilitator sends no CORS headers, so browsers block cross-origin calls.
 * Operators opt specific origins in via {@code x402.http.cors-allowed-origins}.
 * The API is server-to-server by nature, so this stays closed unless asked.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(X402Properties props) {
        List<String> origins = props.http() == null ? List.of()
                : props.http().corsAllowedOriginsOrDefault();
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (origins.isEmpty()) return;
                registry.addMapping("/verify").allowedOrigins(origins.toArray(String[]::new))
                        .allowedMethods("POST");
                registry.addMapping("/settle").allowedOrigins(origins.toArray(String[]::new))
                        .allowedMethods("POST");
                registry.addMapping("/supported").allowedOrigins(origins.toArray(String[]::new))
                        .allowedMethods("GET");
            }
        };
    }
}
