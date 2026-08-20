package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SportsScoreSkillServiceTest {

    @Test
    void createsAndPersistsTheSportsScoreSkill(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        SportsScoreSkillService service = new SportsScoreSkillService(store);

        Skill skill = service.ensureSportsScoreSkill();

        assertEquals("sports-score", skill.name());
        assertTrue(store.contains("sports-score"));
        assertEquals(SportsScoreSkillService.SKILL_VERSION, store.version("sports-score"));
    }

    @Test
    void skillCoversTheAgentAccessibleSources() {
        String text = SportsScoreSkillService.INSTRUCTIONS.toLowerCase();
        assertTrue(text.contains("sportradar") || text.contains("sports data api"), "live sports data APIs");
        assertTrue(text.contains("espn") || text.contains("rss") || text.contains("news"), "news/RSS feeds");
        assertTrue(text.contains("search") && text.contains("panel"), "search-engine score panels");
        assertTrue(text.contains("draftkings") || text.contains("betting") || text.contains("sportsbook"),
                "betting/sportsbook feeds");
        assertTrue(text.contains("never") || text.contains("not") , "must warn against fabricating a score");
    }

    @Test
    void reusesCurrentVersionWithoutRewriting(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        new SportsScoreSkillService(store).ensureSportsScoreSkill();
        // Tamper with the stored instructions; a current-version skill should be returned as-is (not rebuilt).
        store.save(new Skill("sports-score", "d", "CUSTOM EDIT"));
        Skill skill = new SportsScoreSkillService(store).ensureSportsScoreSkill();
        assertEquals("CUSTOM EDIT", skill.instructions());
    }

    @Test
    void rebuildsWhenVersionMarkerIsMissing(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        // A skill saved without a version marker (older build) must be refreshed to the built-in content.
        store.save(new Skill("sports-score", "old", "stale guidance"));
        Skill skill = new SportsScoreSkillService(store).ensureSportsScoreSkill();
        assertTrue(skill.instructions().toLowerCase().contains("live"));
        assertEquals(SportsScoreSkillService.SKILL_VERSION, store.version("sports-score"));
    }
}
