package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void reusesExistingSkillWithoutCallingModel(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        store.save(new Skill("research", "existing", "previously saved guidance"));
        WebResearchService web = webWith(List.of());
        ChatModel model = (messages, maxTokens, temperature) -> {
            throw new IllegalStateException("model must not be called when a skill already exists");
        };

        ResearchSkillService service = new ResearchSkillService(store, web, model);
        Skill skill = service.ensureResearchSkill();

        assertEquals("previously saved guidance", skill.instructions());
    }
}
