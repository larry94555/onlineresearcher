package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalPromptRunnerTest {

    @Test
    void loopRunsResearchAndStopsOnExit(@TempDir Path dir) {
        // Build a fully offline research service that always answers.
        ConversationMemory memory = new ConversationMemory(new TokenEstimator(4), (p, m) -> "", 8100);
        ChatModel model = (messages, maxTokens, temperature) -> {
            String system = messages.stream().filter(x -> x.role().equals("system"))
                    .map(Message::content).findFirst().orElse("");
            if (system.contains("decide whether a user's request")) return "CLEAR";
            if (system.contains("generate web search queries")) return "query";
            if (system.contains("judge whether the gathered")) return "SUFFICIENT";
            if (system.contains("careful research assistant")) return "RESEARCHED ANSWER";
            return "";
        };
        SearchProvider provider = new SearchProvider() {
            @Override public String name() { return "fake"; }
            @Override public boolean enabled() { return true; }
            @Override public List<WebSearchResult> search(String query, int maxResults) {
                return List.of(new WebSearchResult("T", "https://t", "s"));
            }
        };
        SkillStore store = new SkillStore(dir.toString());
        store.save(new Skill("research", "d", "guidance"));
        WebResearchService web = new WebResearchService(List.of(provider));
        ResearchSkillService skillService = new ResearchSkillService(store, web, model);
        ResearchService research = new ResearchService(memory, model, web, skillService,
                256, 2, 2, 16000, 3, 3);

        TerminalPromptRunner runner = new TerminalPromptRunner(research);
        BufferedReader in = new BufferedReader(new StringReader("apples nutrition\nexit\n"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        runner.loop(in, out);

        String printed = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("Online Researcher ready"), "prints the welcome banner");
        assertTrue(printed.contains("RESEARCHED ANSWER"), "prints the researched answer");
        assertTrue(printed.contains("research>"), "shows the ready prompt");
    }
}
