package com.example.onlineresearcher;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchServiceTest {

    /** A fake model that routes by the system prompt's purpose, so each flow step can be scripted. */
    private static class ScriptedModel implements ChatModel {
        String clarity = "CLEAR";
        String queries = "first query\nsecond query";
        final List<String> sufficiencySequence = new ArrayList<>(List.of("SUFFICIENT"));
        String synthesis = "FINAL RESEARCH ANSWER\nSources:\n[1] T - https://t";
        final AtomicInteger sufficiencyCalls = new AtomicInteger();

        @Override
        public String chat(List<Message> messages, Integer maxTokens, Double temperature) {
            String system = messages.stream().filter(m -> m.role().equals("system"))
                    .map(Message::content).findFirst().orElse("");
            if (system.contains("decide whether a user's request")) return clarity;
            if (system.contains("generate web search queries")) return queries;
            if (system.contains("judge whether the gathered")) {
                int i = Math.min(sufficiencyCalls.getAndIncrement(), sufficiencySequence.size() - 1);
                return sufficiencySequence.get(i);
            }
            if (system.contains("careful research assistant")) return synthesis;
            return "";
        }
    }

    /** Fake web provider that records how many times it was queried. */
    private static class CountingProvider implements SearchProvider {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String name() { return "fake"; }
        @Override public boolean enabled() { return true; }
        @Override public List<WebSearchResult> search(String query, int maxResults) {
            calls.incrementAndGet();
            return List.of(new WebSearchResult("Result for " + query, "https://x/" + calls.get(), "snippet"));
        }
    }

    private static ConversationMemory memory() {
        return new ConversationMemory(new TokenEstimator(4), (prev, msgs) -> "", 8100);
    }

    private ResearchService service(ConversationMemory memory, ScriptedModel model, CountingProvider provider,
                                    Path skillsDir, int maxAttempts, int maxClarifications) {
        SkillStore store = new SkillStore(skillsDir.toString());
        store.save(new Skill("research", "desc", "test research guidance"));
        WebResearchService web = new WebResearchService(List.of(provider));
        ResearchSkillService skillService = new ResearchSkillService(store, web, model);
        return new ResearchService(memory, model, web, skillService,
                256, 2, maxAttempts, 16000, 3, maxClarifications);
    }

    @Test
    void asksClarifyingQuestionThenResearchesOnceClear(@TempDir Path dir) {
        ConversationMemory memory = memory();
        ScriptedModel model = new ScriptedModel();
        CountingProvider provider = new CountingProvider();
        ResearchService service = service(memory, model, provider, dir, 2, 3);

        // First turn: model says the request is unclear.
        model.clarity = "UNCLEAR: Which aspect of this topic do you mean?";
        String first = service.handle("tell me about apples");
        assertEquals("Which aspect of this topic do you mean?", first);
        assertTrue(service.awaitingReply());
        assertEquals(0, provider.calls.get(), "no web search until the topic is clear");

        // Second turn: the user clarifies, the model now says it's clear, research runs.
        model.clarity = "CLEAR";
        String answer = service.handle("the nutrition of apples");
        assertFalse(service.awaitingReply());
        assertTrue(answer.contains("FINAL RESEARCH ANSWER"));
        assertTrue(provider.calls.get() > 0, "web search should have run");
        // Both turns were recorded into memory.
        assertTrue(memory.turns().size() >= 4);
    }

    @Test
    void reSearchesWhenInformationIsInsufficient(@TempDir Path dir) {
        ConversationMemory memory = memory();
        ScriptedModel model = new ScriptedModel();
        model.clarity = "CLEAR";
        // First sufficiency check fails, second passes -> two research attempts.
        model.sufficiencySequence.clear();
        model.sufficiencySequence.add("INSUFFICIENT: need more on safety");
        model.sufficiencySequence.add("SUFFICIENT");
        CountingProvider provider = new CountingProvider();
        ResearchService service = service(memory, model, provider, dir, 3, 3);

        String answer = service.handle("safety of a clear topic");

        assertTrue(answer.contains("FINAL RESEARCH ANSWER"));
        assertTrue(model.sufficiencyCalls.get() >= 2, "should evaluate sufficiency more than once");
        // 2 queries per attempt x 2 attempts = 4 provider calls.
        assertEquals(4, provider.calls.get());
    }

    @Test
    void proceedsAfterClarificationCapIsReached(@TempDir Path dir) {
        ConversationMemory memory = memory();
        ScriptedModel model = new ScriptedModel();
        model.clarity = "UNCLEAR: still unclear?";  // always unclear
        CountingProvider provider = new CountingProvider();
        ResearchService service = service(memory, model, provider, dir, 1, 1);

        // First turn asks a clarifying question.
        service.handle("vague");
        assertTrue(service.awaitingReply());
        // Second turn: cap (1) reached, so it researches anyway instead of asking again.
        String answer = service.handle("still vague");
        assertFalse(service.awaitingReply());
        assertTrue(answer.contains("FINAL RESEARCH ANSWER"));
    }
}
