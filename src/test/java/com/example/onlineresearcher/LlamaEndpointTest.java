package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The health check and the chat client take their host from configuration, and {@code llama.host=::1} is an
 * accepted (loopback) setting — so both URIs have to survive a literal IPv6 address.
 */
class LlamaEndpointTest {

    @Test
    void hostnamesAndIpv4AddressesAreUsedAsGiven() {
        assertEquals("http://127.0.0.1:8081", LlamaEndpoint.baseUrl("127.0.0.1", 8081));
        assertEquals("http://localhost:8081", LlamaEndpoint.baseUrl("localhost", 8081));
        assertEquals("http://127.0.0.1:8081", LlamaEndpoint.baseUrl("  ", 8081), "blank falls back to loopback");
    }

    @Test
    void ipv6LiteralsAreBracketed() {
        assertEquals("http://[::1]:8081", LlamaEndpoint.baseUrl("::1", 8081));
        assertEquals("http://[::1]:8081", LlamaEndpoint.baseUrl("[::1]", 8081), "already bracketed");
        assertEquals("http://[fe80::1%25eth0]:8081", LlamaEndpoint.baseUrl("fe80::1%eth0", 8081),
                "a zone id must be percent-encoded in a URI authority");
        assertEquals("http://[fe80::1%25eth0]:8081", LlamaEndpoint.baseUrl("fe80::1%25eth0", 8081),
                "an already-encoded zone id is not double-encoded");
    }

    @Test
    void bothEndpointsAreBuiltFromTheSameAuthority() {
        assertEquals("http://[::1]:8081/health", LlamaEndpoint.health("::1", 8081).toString());
        assertEquals("http://[::1]:8081/v1/chat/completions",
                LlamaEndpoint.chatCompletions("::1", 8081).toString());
    }

    @Test
    void anIpv6RequestIsAcceptedByTheHttpClient() {
        // The unbracketed form ("http://::1:8081/health") is what HttpRequest rejects, which left the health
        // poll running its full timeout and every chat call failing.
        assertDoesNotThrow(() -> HttpRequest.newBuilder(LlamaEndpoint.health("::1", 8081)).GET().build());
        assertDoesNotThrow(() -> LlamaClient.buildRequest("::1", 8081, "s3cret", "{}"));
        assertEquals("http://[::1]:8081/v1/chat/completions",
                LlamaClient.buildRequest("::1", 8081, "", "{}").uri().toString());
    }
}
