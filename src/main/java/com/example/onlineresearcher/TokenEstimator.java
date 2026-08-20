package com.example.onlineresearcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cheap, dependency-free token estimator. llama-server does the real tokenization, but the application
 * needs to estimate sizes locally to decide when to compact memory. We approximate using a fixed
 * characters-per-token ratio (~4 chars/token for English text, the common rule of thumb) plus a small
 * per-message overhead for the role and chat-format framing. The ratio is configurable via
 * {@code memory.chars-per-token} so it can be tuned for other models later.
 */
@Component
public class TokenEstimator {

    /** Approximate framing overhead (role markers, separators) charged to every message. */
    static final int PER_MESSAGE_OVERHEAD = 4;

    private final int charsPerToken;

    public TokenEstimator(@Value("${memory.chars-per-token:4}") int charsPerToken) {
        this.charsPerToken = Math.max(1, charsPerToken);
    }

    /** Estimated tokens for a piece of text. */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil((double) text.length() / charsPerToken);
    }

    /** Estimated tokens for an assembled list of chat messages. */
    public int estimate(List<Message> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (Message message : messages) {
            total += PER_MESSAGE_OVERHEAD + estimate(message.role()) + estimate(message.content());
        }
        return total;
    }
}
