package org.cardanofoundation.x402.facilitator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "x402")
public record X402Properties(List<NetworkEntry> networks,
                             Verification verification,
                             Settle settle,
                             DuplicateCache duplicateCache,
                             Http http,
                             Masumi masumi,
                             Security security) {

    public record NetworkEntry(String id, Boolean required, ChainConfig chain, SlotConfig slotConfig) {
        public boolean isRequired() {
            return required == null || required;
        }
    }

    public record ChainConfig(Blockfrost blockfrost) {
    }

    /**
     * The Blockfrost provider (cardano-client-lib {@code BFBackendService}). Point
     * {@code baseUrl} at hosted Blockfrost <em>or</em> a standalone yaci-store's
     * Blockfrost-compatible endpoint — the facilitator treats them identically.
     */
    public record Blockfrost(String baseUrl, String projectId) {
    }

    /** Per-network slot arithmetic anchor; defaults ship in NetworkClock, this overrides. */
    public record SlotConfig(Long zeroSlot, Long zeroTimeEpochSeconds, Long slotLengthMs) {
    }

    public record Verification(Integer maxTxBytes, String scriptDatumPolicy) {
        public int maxTxBytesOrDefault() {
            return maxTxBytes == null ? 32768 : maxTxBytes;
        }

        public String scriptDatumPolicyOrDefault() {
            return scriptDatumPolicy == null ? "strict" : scriptDatumPolicy;
        }
    }

    public record Settle(Duration confirmationTimeout, Integer confirmationDepth, Duration pollInterval,
                         Boolean acceptMempool, Boolean idempotentReplay, Duration stabilityWindow,
                         Duration reconcileHorizon) {
        public Duration confirmationTimeoutOrDefault() {
            return confirmationTimeout == null ? Duration.ofSeconds(180) : confirmationTimeout;
        }

        public int confirmationDepthOrDefault() {
            return confirmationDepth == null ? 1 : confirmationDepth;
        }

        public Duration pollIntervalOrDefault() {
            return pollInterval == null ? Duration.ofSeconds(3) : pollInterval;
        }

        public boolean acceptMempoolOrDefault() {
            return acceptMempool != null && acceptMempool;
        }

        public boolean idempotentReplayOrDefault() {
            return idempotentReplay != null && idempotentReplay;
        }

        public Duration stabilityWindowOrDefault() {
            return stabilityWindow == null ? Duration.ofMinutes(10) : stabilityWindow;
        }

        public Duration reconcileHorizonOrDefault() {
            return reconcileHorizon == null ? Duration.ofHours(24) : reconcileHorizon;
        }
    }

    public record DuplicateCache(Duration ttl) {
        public Duration ttlOrDefault() {
            return ttl == null ? Duration.ofSeconds(120) : ttl;
        }
    }

    public record Http(Integer maxRequestBytes, List<String> corsAllowedOrigins) {
        public int maxRequestBytesOrDefault() {
            return maxRequestBytes == null ? 65536 : maxRequestBytes;
        }

        /** Allowed CORS origins; empty (default) = no cross-origin access. */
        public List<String> corsAllowedOriginsOrDefault() {
            return corsAllowedOrigins == null ? List.of() : corsAllowedOrigins;
        }
    }

    /** Per-network ({@code cardano:preprod} etc.) allowed escrow script-hash hex list. */
    public record Masumi(Map<String, List<String>> allowedScriptHashes) {
    }

    /**
     * Optional edge protections, all off by default so the facilitator stays
     * open unless an operator opts in.
     */
    public record Security(List<String> apiKeys, RateLimit rateLimit) {
        public List<String> apiKeysOrDefault() {
            return apiKeys == null ? List.of() : apiKeys;
        }

        public int requestsPerMinuteOrDefault() {
            return rateLimit == null ? 0 : rateLimit.requestsPerMinuteOrDefault();
        }

        public record RateLimit(Integer requestsPerMinute) {
            public int requestsPerMinuteOrDefault() {
                return requestsPerMinute == null ? 0 : requestsPerMinute;
            }
        }
    }
}
