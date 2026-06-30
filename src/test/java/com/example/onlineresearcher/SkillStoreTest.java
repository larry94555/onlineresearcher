package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillStoreTest {

    @Test
    void savesAndLoadsRoundTrip(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        assertFalse(store.contains("research"));

        Skill skill = new Skill("research", "How to research", "1. Search.\n2. Verify.");
        store.save(skill);

        assertTrue(store.contains("research"));
        Skill loaded = store.get("research");
        assertEquals("research", loaded.name());
        assertEquals("How to research", loaded.description());
        assertEquals("1. Search.\n2. Verify.", loaded.instructions());
    }

    @Test
    void getMissingSkillReturnsNull(@TempDir Path dir) {
        SkillStore store = new SkillStore(dir.toString());
        assertNull(store.get("nope"));
    }

    @Test
    void parseHandlesHeaderAndSeparator() {
        String content = "name: research\ndescription: A desc\n---\nLine one\nLine two";
        Skill skill = SkillStore.parse("fallback", content);
        assertEquals("research", skill.name());
        assertEquals("A desc", skill.description());
        assertEquals("Line one\nLine two", skill.instructions());
    }

    @Test
    void parseWithoutSeparatorUsesWholeBodyAsInstructions() {
        Skill skill = SkillStore.parse("research", "just some guidance with no header");
        assertEquals("research", skill.name());
        assertEquals("just some guidance with no header", skill.instructions());
    }

    @Test
    void renderProducesParseableDocument() {
        Skill original = new Skill("research", "desc with\nnewline", "body text");
        Skill roundTrip = SkillStore.parse("x", SkillStore.render(original));
        assertEquals("research", roundTrip.name());
        assertEquals("desc with newline", roundTrip.description());
        assertEquals("body text", roundTrip.instructions());
    }
}
