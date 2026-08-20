package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSkillServiceTest {

    private static WebResearchService webWith(List<WebSearchResult> results) {
        SearchProvider provider = new SearchProvider() {
            @Override public String name() { return "fake"; }
            @Override public boolean enabled() { return true; }
            @Override public List<WebSearchResult> search(String query, int maxResults) { return results; }
        };
        return new WebResearchService(List.of(provider));
    }

    @Test
    void bootstrapsSkillFromWebAndModelThenPersists(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        WebResearchService web = webWith(List.of(
                new WebSearchResult("Best practices", "https://guide", "use multiple sources")));
        ChatModel model = (messages, maxTokens, temperature) -> "1. Synthesized research guidance.";

        ResearchSkillService service = new ResearchSkillService(store, web, model);
        Skill skill = service.ensureResearchSkill();

        assertEquals("research", skill.name());
        assertTrue(skill.instructions().contains("Synthesized research guidance"));
        // Persisted for reuse on later runs.
        assertTrue(store.contains("research"));
    }

    @Test
    void fallsBackToBuiltInGuidanceWhenNoWebResults(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        WebResearchService web = webWith(List.of());
        ChatModel model = (messages, maxTokens, temperature) -> {
            throw new IllegalStateException("model should not be called when there are no web results");
        };

        ResearchSkillService service = new ResearchSkillService(store, web, model);
        Skill skill = service.ensureResearchSkill();

        assertEquals(ResearchSkillService.DEFAULT_INSTRUCTIONS.strip(), skill.instructions());
    }

    @Test
    void reusesCurrentVersionSkillWithoutRebuilding(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        WebResearchService web = webWith(List.of(
                new WebSearchResult("Best practices", "https://guide", "use multiple sources")));
        // First service builds the skill and stamps it with the current version.
        new ResearchSkillService(store, web, (m, mt, t) -> "1. Built guidance.").ensureResearchSkill();

        // A second service must reuse the current-version skill without calling the model again.
        ChatModel forbidden = (m, mt, t) -> {
            throw new IllegalStateException("model must not be called for a current-version skill");
        };
        Skill skill = new ResearchSkillService(store, web, forbidden).ensureResearchSkill();
        assertTrue(skill.instructions().contains("Built guidance"));
    }

    @Test
    void rebuildsOlderUnversionedSkillSoNewStrategyIsApplied(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        // An existing skill saved by a previous version has no version marker.
        store.save(new Skill("research", "old", "stale guidance"));
        WebResearchService web = webWith(List.of());   // empty -> built-in default, no model call

        Skill skill = new ResearchSkillService(store, web, (m, mt, t) -> "unused").ensureResearchSkill();

        assertFalse(skill.instructions().equals("stale guidance"), "stale skill should be rebuilt");
        assertTrue(skill.instructions().toLowerCase().contains("authoritative"));
    }

    @Test
    void defaultInstructionsIncludeAuthoritativeSourceStrategy() {
        String d = ResearchSkillService.DEFAULT_INSTRUCTIONS.toLowerCase();
        assertTrue(d.contains("identify the authoritative"), "should identify authoritative sources");
        assertTrue(d.contains("prefer those authoritative sources"), "should prefer authoritative sources");
        assertTrue(d.contains("against those authoritative sources"), "should check facts against them");
    }
}
