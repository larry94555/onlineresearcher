package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void emptyOrNullTextIsZeroTokens() {
        TokenEstimator estimator = new TokenEstimator(4);
        assertEquals(0, estimator.estimate((String) null));
        assertEquals(0, estimator.estimate(""));
    }

    @Test
    void estimatesByCharactersPerToken() {
        TokenEstimator estimator = new TokenEstimator(4);
        // 8 chars / 4 = 2 tokens.
        assertEquals(2, estimator.estimate("12345678"));
        // 9 chars / 4 = 2.25 -> ceil 3.
        assertEquals(3, estimator.estimate("123456789"));
    }

    @Test
    void messageListAddsPerMessageOverhead() {
        TokenEstimator estimator = new TokenEstimator(4);
        int single = estimator.estimate(List.of(Message.user("12345678")));
        // overhead(4) + role "user"(1) + content(2) = 7
        assertEquals(7, single);
        int two = estimator.estimate(List.of(Message.user("12345678"), Message.assistant("12345678")));
        assertTrue(two > single);
    }
}
