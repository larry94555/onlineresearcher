package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryTest {

    private static ConversationMemory memory(int maxTokens, Summarizer summarizer) {
        return new ConversationMemory(new TokenEstimator(4), summarizer, maxTokens);
    }

    @Test
    void includesPriorTurnsWhenWithinBudget() {
        ConversationMemory memory = memory(8100, (prev, msgs) -> "SUMMARY");
        memory.recordExchange("first question", "first answer");

        List<Message> request = memory.prepareRequest("system", "second question", 100);

        // system + 2 prior turns + new user prompt.
        assertEquals("system", request.get(0).role());
        assertTrue(request.stream().anyMatch(m -> m.content().contains("first question")));
        assertTrue(request.stream().anyMatch(m -> m.content().contains("first answer")));
        assertEquals("second question", request.get(request.size() - 1).content());
    }

    @Test
    void compactsOldTurnsIntoSummaryWhenOverBudget() {
        // Tiny budget forces compaction; the fake summarizer records that it was called.
        boolean[] summarized = {false};
        ConversationMemory memory = memory(40, (prev, msgs) -> {
            summarized[0] = true;
            return "COMPACTED";
        });
        memory.recordExchange("a very long earlier question that should be evicted",
                "a very long earlier answer that should be evicted too");

        List<Message> request = memory.prepareRequest("sys", "new prompt", 5);

        assertTrue(summarized[0], "summarizer should have been invoked");
        assertTrue(memory.summary().contains("COMPACTED"));
        // The verbatim old turn should no longer be present.
        assertFalse(request.stream().anyMatch(m -> m.content().contains("earlier question")));
    }

    @Test
    void assembledRequestStaysWithinBudget() {
        int max = 200;
        int reserve = 50;
        ConversationMemory memory = memory(max, (prev, msgs) -> "S");
        TokenEstimator estimator = new TokenEstimator(4);
        for (int i = 0; i < 20; i++) {
            memory.recordExchange("question number " + i + " with some padding text here",
                    "answer number " + i + " with some more padding text here as well");
        }

        List<Message> request = memory.prepareRequest("system prompt", "the newest user prompt", reserve);

        assertTrue(estimator.estimate(request) <= max - reserve,
                "memory + prompt must stay within the token budget");
    }
}
