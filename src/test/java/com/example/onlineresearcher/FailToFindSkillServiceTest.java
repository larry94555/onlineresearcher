package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailToFindSkillServiceTest {

    @Test
    void createsPersistsAndVersionsTheSkill(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        Skill skill = new FailToFindSkillService(store).ensureFailToFindSkill();

        assertEquals("failing-to-find-information", skill.name());
        assertTrue(store.contains("failing-to-find-information"));
        assertEquals(FailToFindSkillService.SKILL_VERSION, store.version("failing-to-find-information"));
    }

    @Test
    void skillStatesTheFallbackPolicyAndExactNotFoundSentence() {
        String text = FailToFindSkillService.INSTRUCTIONS;
        String lower = text.toLowerCase();
        assertTrue(lower.contains("no api key") || lower.contains("without"), "agent-accessible/keyless sources");
        assertTrue(lower.contains("second pass") || lower.contains("query those sources"), "second query pass");
        // The exact not-found sentence the agent must use is embedded in the guidance.
        assertTrue(text.contains(ResearchService.NOT_FOUND_MESSAGE), "exact not-found sentence");
        assertTrue(lower.contains("clarifying question"), "optional clarifying question");
        // A search that found nothing is a not-found result, never a negative finding. (Line-wrapped in
        // the guidance, so compare on a single-spaced copy.)
        String flowed = lower.replaceAll("\\s+", " ");
        assertTrue(flowed.contains("never as proof that there is nothing to find"),
                "must not license concluding absence from a failed search");
    }

    @Test
    void rebuildsWhenVersionMarkerIsMissing(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        store.save(new Skill("failing-to-find-information", "old", "stale"));   // no version marker
        Skill skill = new FailToFindSkillService(store).ensureFailToFindSkill();
        assertTrue(skill.instructions().contains(ResearchService.NOT_FOUND_MESSAGE));
        assertEquals(FailToFindSkillService.SKILL_VERSION, store.version("failing-to-find-information"));
    }
}
