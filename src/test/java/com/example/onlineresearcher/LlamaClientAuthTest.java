package com.example.onlineresearcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the authenticated request path: a llama-server started with an API key rejects unauthenticated
 * calls, and a non-loopback bind is only allowed with a key, so the client must send one when configured.
 */
class LlamaClientAuthTest {

    @Test
    void bearerHeaderIsSentOnlyWhenAKeyIsConfigured() {
        HttpRequest withKey = LlamaClient.buildRequest("127.0.0.1", 8081, "s3cret", "{}");
        assertEquals("Bearer s3cret", withKey.headers().firstValue("Authorization").orElse(""));

        HttpRequest withoutKey = LlamaClient.buildRequest("127.0.0.1", 8081, "", "{}");
        assertFalse(withoutKey.headers().firstValue("Authorization").isPresent(),
                "no key configured means no Authorization header");
        assertEquals("http://127.0.0.1:8081/v1/chat/completions", withoutKey.uri().toString());
    }

    @Test
    void chatSucceedsAgainstAServerThatRequiresTheKey() throws Exception {
        AtomicReference<String> seenAuthorization = new AtomicReference<>();
        HttpServer server = keyRequiringServer("s3cret", seenAuthorization);
        try {
            LlamaClient client = configuredClient(server.getAddress().getPort(), "s3cret");

            String reply = client.chat(List.of(Map.of("role", "user", "content", "hello")), 16, 0.0);

            assertEquals("authenticated reply", reply);
            assertEquals("Bearer s3cret", seenAuthorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatWithoutTheKeyIsRejectedByThatServer() throws Exception {
        HttpServer server = keyRequiringServer("s3cret", new AtomicReference<>());
        try {
            LlamaClient client = configuredClient(server.getAddress().getPort(), "");

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> client.chat(List.of(Map.of("role", "user", "content", "hello")), 16, 0.0));

            assertTrue(error.getMessage().contains("401"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static LlamaClient configuredClient(int port, String apiKey) {
        LlamaClient client = new LlamaClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "host", "127.0.0.1");
        ReflectionTestUtils.setField(client, "port", port);
        ReflectionTestUtils.setField(client, "apiKey", apiKey);
        return client;
    }

    /** A stand-in for llama-server started with --api-key: 401 unless the bearer token matches. */
    private static HttpServer keyRequiringServer(String key, AtomicReference<String> seenAuthorization)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            seenAuthorization.set(authorization);
            byte[] body;
            int status;
            if (("Bearer " + key).equals(authorization)) {
                status = 200;
                body = "{\"choices\":[{\"message\":{\"content\":\"authenticated reply\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 401;
                body = "{\"error\":\"invalid api key\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }
}
