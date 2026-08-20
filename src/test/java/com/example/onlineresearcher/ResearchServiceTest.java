package com.example.onlineresearcher;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchServiceTest {

    /** A fake model that routes by the system prompt's purpose, so each flow step can be scripted. */
    private static class ScriptedModel implements ChatModel {
        String clarity = "CLEAR";
        String queries = "first query\nsecond query";
        String queriesAfterFeedback = "third query\nfourth query";
        final List<String> sufficiencySequence = new ArrayList<>(List.of("SUFFICIENT"));
        String synthesis = "FINAL RESEARCH ANSWER\nSources:\n[1] T - https://t";
        final AtomicInteger sufficiencyCalls = new AtomicInteger();

        @Override
        public String chat(List<Message> messages, Integer maxTokens, Double temperature) {
            String system = messages.stream().filter(m -> m.role().equals("system"))
                    .map(Message::content).findFirst().orElse("");
            if (system.contains("decide whether a user's request")) return clarity;
            if (system.contains("web search engine queries")) {
                boolean hasFeedback = messages.stream()
                        .anyMatch(m -> m.content().contains("previous search returned too little"));
                return hasFeedback ? queriesAfterFeedback : queries;
            }
            if (system.contains("judge whether the gathered")) {
                int i = Math.min(sufficiencyCalls.getAndIncrement(), sufficiencySequence.size() - 1);
                return sufficiencySequence.get(i);
            }
            if (system.contains("careful research assistant")) return synthesis;
            return "";
        }
    }

    /** Fake web provider that records how many times it was queried and with what. */
    private static class CountingProvider implements SearchProvider {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> queries = new ArrayList<>();
        @Override public String name() { return "fake"; }
        @Override public boolean enabled() { return true; }
        @Override public List<WebSearchResult> search(String query, int maxResults) {
            calls.incrementAndGet();
            queries.add(query);
            return List.of(new WebSearchResult("Result for " + query, "https://x/" + calls.get(), "snippet"));
        }
    }

    private static ConversationMemory memory() {
        return new ConversationMemory(new TokenEstimator(4), (prev, msgs) -> "", 8100);
    }

    private ResearchService service(ConversationMemory memory, ScriptedModel model, CountingProvider provider,
                                    Path skillsDir, int maxAttempts, int maxClarifications) {
        SkillStore store = new SkillStore(skillsDir.toString());
        WebResearchService web = new WebResearchService(List.of(provider));
        ResearchSkillService skillService = new ResearchSkillService(store, web, model);
        // Pre-build + version-stamp the skill via an empty web so the per-turn ensureResearchSkill() does
        // not rebuild (and issue a bootstrap web search) during the test and skew provider call counts.
        new ResearchSkillService(store, new WebResearchService(List.of()), model).ensureResearchSkill();
        return new ResearchService(memory, model, web, skillService, new SportsScoreSkillService(store),
                new FailToFindSkillService(store), 256, 2, maxAttempts, 16000, 3, maxClarifications);
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
        // A second attempt ran with NEW (deduplicated) queries, so more searches than a single attempt.
        assertTrue(provider.calls.get() >= 3, "second attempt should issue fresh queries: " + provider.calls.get());
    }

    @Test
    void answersNewTopicWithoutBleedingFromPreviousConversation(@TempDir Path dir) {
        ConversationMemory memory = memory();
        // A substantial prior answer is already in memory (as the real Jacobsthal turn was).
        memory.recordExchange("old jacobsthal question",
                "PRIOR_ANSWER: the jacobsthal numbers are an integer sequence named after Ernst Jacobsthal");

        AtomicBoolean synthesisSawPrior = new AtomicBoolean(false);
        ChatModel model = (messages, mt, t) -> {
            String system = messages.stream().filter(m -> m.role().equals("system"))
                    .map(Message::content).findFirst().orElse("");
            boolean sawPrior = messages.stream().anyMatch(m -> m.content().contains("PRIOR_ANSWER"));
            if (system.contains("decide whether a user's request")) return "CLEAR";
            if (system.contains("web search engine queries")) return "score query";
            if (system.contains("judge whether the gathered")) return "SUFFICIENT";
            if (system.contains("careful research assistant")) {
                if (sawPrior) synthesisSawPrior.set(true);
                return "NEW TOPIC ANSWER";
            }
            return "";
        };
        CountingProvider provider = new CountingProvider();
        SkillStore store = new SkillStore(dir.toString());
        WebResearchService web = new WebResearchService(List.of(provider));
        new ResearchSkillService(store, new WebResearchService(List.of()), model).ensureResearchSkill();
        ResearchSkillService skillService = new ResearchSkillService(store, web, model);
        ResearchService service = new ResearchService(memory, model, web, skillService,
                new SportsScoreSkillService(store), new FailToFindSkillService(store), 256, 2, 1, 16000, 3, 3);

        String answer = service.handle("what was the score in today's match");

        assertFalse(synthesisSawPrior.get(),
                "synthesis must not receive the previous unrelated conversation");
        assertTrue(answer.contains("NEW TOPIC ANSWER"), answer);
    }

    @Test
    void appliesSportsScoreSkillForScoreQuestions(@TempDir Path dir) {
        ConversationMemory memory = memory();
        AtomicBoolean sawSportsSkill = new AtomicBoolean(false);
        ChatModel model = (messages, mt, t) -> {
            String system = messages.stream().filter(m -> m.role().equals("system"))
                    .map(Message::content).findFirst().orElse("");
            if (system.contains("Sportradar")) sawSportsSkill.set(true);   // a sports-score skill marker
            if (system.contains("decide whether a user's request")) return "CLEAR";
            if (system.contains("web search engine queries")) return "japan brazil score";
            if (system.contains("judge whether the gathered")) return "SUFFICIENT";
            if (system.contains("careful research assistant")) return "ANSWER";
            return "";
        };
        CountingProvider provider = new CountingProvider();
        SkillStore store = new SkillStore(dir.toString());
        WebResearchService web = new WebResearchService(List.of(provider));
        new ResearchSkillService(store, new WebResearchService(List.of()), model).ensureResearchSkill();
        ResearchSkillService skillService = new ResearchSkillService(store, web, model);
        ResearchService service = new ResearchService(memory, model, web, skillService,
                new SportsScoreSkillService(store), new FailToFindSkillService(store), 256, 2, 1, 16000, 3, 3);

        service.handle("what was the score in the world cup game between japan and brazil");

        assertTrue(sawSportsSkill.get(), "the sports-score skill should be injected for a score question");
    }

    @Test
    void fallsBackToAgentAccessibleSitesBeforeGivingUp(@TempDir Path dir) {
        ConversationMemory memory = memory();
        AtomicInteger synthCalls = new AtomicInteger();
        ChatModel model = (messages, mt, t) -> {
            String system = messages.stream().filter(m -> m.role().equals("system"))
                    .map(Message::content).findFirst().orElse("");
            if (system.contains("decide whether a user's request")) return "CLEAR";
            if (system.contains("web search engine queries")) return "obscure topic";
            if (system.contains("judge whether the gathered")) return "INSUFFICIENT: missing";
            if (system.contains("List up to 4 specific web sources")) return "specialsite.org";
            if (system.contains("careful research assistant")) {
                // First synthesis can't answer; after the fallback adds sources, the second one can.
                return synthCalls.incrementAndGet() == 1 ? "NEED_MORE_SOURCES" : "FALLBACK ANSWER";
            }
            return "";
        };
        CountingProvider provider = new CountingProvider();
        SkillStore store = new SkillStore(dir.toString());
        WebResearchService web = new WebResearchService(List.of(provider));
        new ResearchSkillService(store, new WebResearchService(List.of()), model).ensureResearchSkill();
        ResearchService service = new ResearchService(memory, model, web,
                new ResearchSkillService(store, web, model), new SportsScoreSkillService(store),
                new FailToFindSkillService(store), 256, 2, 1, 16000, 3, 3);

        String answer = service.handle("an obscure topic with no easy answer");

        assertTrue(answer.contains("FALLBACK ANSWER"), answer);
        // The fallback issued site-targeted queries using the suggested source.
        assertTrue(provider.queries.stream().anyMatch(q -> q.contains("specialsite")),
                "fallback should query the suggested site: " + provider.queries);
    }

    @Test
    void reportsClearNotFoundWhenEverythingFails(@TempDir Path dir) {
        ConversationMemory memory = memory();
        ChatModel model = (messages, mt, t) -> {
            String system = messages.stream().filter(m -> m.role().equals("system"))
                    .map(Message::content).findFirst().orElse("");
            if (system.contains("decide whether a user's request")) return "CLEAR";
            if (system.contains("web search engine queries")) return "topic";
            if (system.contains("judge whether the gathered")) return "INSUFFICIENT: missing";
            if (system.contains("List up to 4 specific web sources")) return "somewhere.org";
            if (system.contains("found nothing usable")) return "NONE";       // no useful clarification
            if (system.contains("careful research assistant")) return "NEED_MORE_SOURCES";
            return "";
        };
        CountingProvider provider = new CountingProvider();
        SkillStore store = new SkillStore(dir.toString());
        WebResearchService web = new WebResearchService(List.of(provider));
        new ResearchSkillService(store, new WebResearchService(List.of()), model).ensureResearchSkill();
        ResearchService service = new ResearchService(memory, model, web,
                new ResearchSkillService(store, web, model), new SportsScoreSkillService(store),
                new FailToFindSkillService(store), 256, 2, 1, 16000, 3, 3);

        String answer = service.handle("something unfindable");

        assertEquals(ResearchService.NOT_FOUND_MESSAGE, answer);
    }

    @Test
    void notFoundAppendsClarifyingQuestionWhenItHelps(@TempDir Path dir) {
        ConversationMemory memory = memory();
        ChatModel model = (messages, mt, t) -> {
            String system = messages.stream().filter(m -> m.role().equals("system"))
                    .map(Message::content).findFirst().orElse("");
            if (system.contains("decide whether a user's request")) return "CLEAR";
            if (system.contains("web search engine queries")) return "topic";
            if (system.contains("judge whether the gathered")) return "INSUFFICIENT: missing";
            if (system.contains("List up to 4 specific web sources")) return "";   // no sites suggested
            if (system.contains("found nothing usable")) return "Which year are you asking about?";
            if (system.contains("careful research assistant")) return "NEED_MORE_SOURCES";
            return "";
        };
        CountingProvider provider = new CountingProvider();
        SkillStore store = new SkillStore(dir.toString());
        WebResearchService web = new WebResearchService(List.of(provider));
        new ResearchSkillService(store, new WebResearchService(List.of()), model).ensureResearchSkill();
        ResearchService service = new ResearchService(memory, model, web,
                new ResearchSkillService(store, web, model), new SportsScoreSkillService(store),
                new FailToFindSkillService(store), 256, 2, 1, 16000, 3, 3);

        String answer = service.handle("something unfindable");

        assertTrue(answer.startsWith(ResearchService.NOT_FOUND_MESSAGE), answer);
        assertTrue(answer.contains("Which year are you asking about?"), answer);
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
