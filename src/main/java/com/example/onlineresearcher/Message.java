package com.example.onlineresearcher;

import java.util.Map;

/**
 * A single chat message in a conversation. {@code role} is one of {@code system}, {@code user}, or
 * {@code assistant}; {@code content} is the text.
 */
public record Message(String role, String content) {

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message system(String content) {
        return new Message("system", content);
    }

    /** The wire shape llama-server's {@code /v1/chat/completions} endpoint expects. */
    public Map<String, Object> toMap() {
        return Map.of("role", role, "content", content);
    }
}
