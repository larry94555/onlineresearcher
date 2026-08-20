package com.example.onlineresearcher;

import java.net.URI;

/**
 * Builds the URIs of the local llama-server. Both callers — the manager's health check and the client's
 * chat request — take their host from configuration, and a literal IPv6 address must be bracketed inside a
 * URI authority ({@code http://[::1]:8081/health}). Concatenated raw it produces {@code http://::1:8081/},
 * which {@link java.net.http.HttpRequest} rejects, so the health poll runs out its full timeout and every
 * chat call fails. One builder, so the two call sites cannot disagree about it.
 */
final class LlamaEndpoint {

    private LlamaEndpoint() {
    }

    /** The {@code /health} endpoint the launcher polls. */
    static URI health(String host, int port) {
        return URI.create(baseUrl(host, port) + "/health");
    }

    /** The OpenAI-compatible chat endpoint. */
    static URI chatCompletions(String host, int port) {
        return URI.create(baseUrl(host, port) + "/v1/chat/completions");
    }

    /** {@code http://host:port}, with a literal IPv6 host bracketed. */
    static String baseUrl(String host, int port) {
        return "http://" + authority(host) + ":" + port;
    }

    /**
     * The host as it may appear in a URI authority: hostnames and IPv4 literals unchanged, IPv6 literals
     * bracketed (accepting an already-bracketed value), with any zone id percent-encoded as the URI syntax
     * requires ({@code fe80::1%eth0} -> {@code [fe80::1%25eth0]}).
     */
    static String authority(String host) {
        String value = host == null ? "" : host.trim();
        if (value.isEmpty()) return "127.0.0.1";
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.indexOf(':') < 0) return value;   // hostname or IPv4 literal
        return "[" + value.replace("%25", "%").replace("%", "%25") + "]";
    }
}
