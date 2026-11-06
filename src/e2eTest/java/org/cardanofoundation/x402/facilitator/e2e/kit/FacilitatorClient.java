package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Raw HTTP to one facilitator instance. Returns status, headers and the body
 * as sent — no status checks, no deserialization into main's records — so a
 * test pins exactly what a real client would see.
 */
public final class FacilitatorClient {

    public static final ObjectMapper JSON = new ObjectMapper();

    /**
     * /settle may wait for inclusion and confirmations; on the devnet (1 s slots)
     * that is seconds, but the deadline must comfortably exceed the facilitator's
     * own provider timeouts so a slow answer is never mistaken for none.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

    @Getter
    private final String baseUrl;
    private final HttpClient http;

    public FacilitatorClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static FacilitatorClient defaultInstance() {
        return new FacilitatorClient(E2eEnv.facilitatorUrl());
    }

    public static FacilitatorClient mempoolInstance() {
        return new FacilitatorClient(E2eEnv.facilitatorMempoolUrl());
    }

    public Response verify(Object body) {
        return post("/verify", toJson(body));
    }

    public Response settle(Object body) {
        return post("/settle", toJson(body));
    }

    public Response get(String path) {
        return send(request(path).GET().build());
    }

    public Response get(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = request(path).GET();
        headers.forEach(builder::header);
        return send(builder.build());
    }

    public Response post(String path, String rawBody) {
        return post(path, rawBody, "application/json");
    }

    public Response post(String path, String rawBody, String contentType) {
        return send(request(path)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(rawBody))
                .build());
    }

    /**
     * POSTs without a Content-Length (chunked transfer encoding), so a size
     * limit can only be enforced while the body streams in.
     */
    public Response postStreamed(String path, byte[] rawBody) {
        return send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(rawBody)))
                .build());
    }

    /** Any method, any body, any headers: for the off-contract requests. */
    public Response send(String method, String path, String rawBody, Map<String, String> headers) {
        HttpRequest.Builder builder = request(path).method(method, rawBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(rawBody));
        headers.forEach(builder::header);
        return send(builder.build());
    }

    public static String toJson(Object body) {
        if (body instanceof String s) return s;
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize request body", e);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(REQUEST_TIMEOUT);
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(request.method() + " " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + request.uri(), e);
        }
    }

    public record Response(int status, HttpHeaders headers, String body) {

        /** The body as JSON; fails the test with the raw body when it isn't. */
        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (JsonProcessingException e) {
                throw new AssertionError("HTTP " + status + " body is not JSON: " + body, e);
            }
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        @Override
        public String toString() {
            return "HTTP " + status + " " + body;
        }
    }
}
