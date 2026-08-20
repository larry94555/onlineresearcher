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

        assertTrue(skill.instructions().startsWith(ResearchSkillService.DEFAULT_INSTRUCTIONS.strip()));
        assertTrue(skill.instructions().endsWith(ResearchSkillService.REQUIRED_POLICY.strip()));
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

    @Test
    void everySkillCarriesTheRelationshipPolicyWhicheverPathBuiltIt(@TempDir Path dir) {
        // The online path is the normal one: any nonempty search sends the guidance through the model, whose
        // synthesized text is written from web snippets and cannot be trusted to state the invariant. The
        // guidance is injected into every step of the flow, so it must never contradict the prompts.
        ChatModel omitsThePolicy = (m, mt, t) ->
                "1. Search widely.\n2. If no source links two subjects, conclude they are unrelated.";
        WebResearchService web = webWith(List.of(
                new WebSearchResult("Best practices", "https://guide", "use multiple sources")));

        Skill synthesized = new ResearchSkillService(new SkillStore(dir.resolve("online").toString()),
                web, omitsThePolicy).ensureResearchSkill();
        Skill builtIn = new ResearchSkillService(new SkillStore(dir.resolve("offline").toString()),
                webWith(List.of()), omitsThePolicy).ensureResearchSkill();

        assertTrue(synthesized.instructions().contains("Search widely"), "the synthesized text is kept");
        for (Skill skill : List.of(synthesized, builtIn)) {
            // Single-spaced: the rules are line-wrapped in the guidance.
            String lower = skill.instructions().toLowerCase().replaceAll("\\s+", " ");
            assertTrue(skill.instructions().contains(ResearchSkillService.REQUIRED_POLICY.strip()),
                    "the non-negotiable rules must survive every build path: " + skill.instructions());
            assertTrue(lower.contains("unresolved"), "an unaddressed relationship is unresolved");
            assertTrue(lower.contains("only when a source actually says so"),
                    "a negative conclusion needs a source that states it");
            assertFalse(lower.contains("absence of any linking source is itself evidence"),
                    "absence of evidence must not be taught as evidence of absence");
        }
    }

    @Test
    void theSynthesisPromptAsksForTheRelationshipRuleToo() {
        assertTrue(ResearchSkillService.REQUIRED_POLICY.contains("unresolved"));
        // Appending is the guarantee; asking is what keeps the synthesized text from arguing against it.
        assertTrue(ResearchSkillService.SYNTHESIS_SYSTEM.contains(
                        "rather than as proof that no connection exists"),
                ResearchSkillService.SYNTHESIS_SYSTEM);
    }
}
