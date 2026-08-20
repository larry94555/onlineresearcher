package com.example.onlineresearcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the {@code failing-to-find-information} skill: the policy the agent follows when a first search does
 * not yield the answer. Its content is built in (and versioned/persisted via {@link SkillStore}). The skill
 * is guidance injected into the prompts; the matching behavior — a fallback pass that asks which keyless,
 * agent-accessible sites cover the topic and queries them before giving up, followed by a clear not-found
 * statement and an optional clarifying question — is enforced in {@link ResearchService}.
 */
@Component
public class FailToFindSkillService {
    private static final Logger log = LoggerFactory.getLogger(FailToFindSkillService.class);

    static final String SKILL_NAME = "failing-to-find-information";
    static final int SKILL_VERSION = 1;
    private static final String SKILL_DESCRIPTION =
            "What to do when a first search finds nothing: try agent-accessible sources, then report clearly.";

    static final String INSTRUCTIONS = """
            Follow this when a first search does not produce the answer:
            1. Do NOT give up after one search. First, work out which specific web sites or sources would have
               this information AND are openly accessible to automated agents with no API key, login, or
               payment (e.g. Wikipedia, official sites, well-known reference/encyclopedic sites, public docs).
            2. Query those sources specifically (add the source/site to the search terms) before concluding
               anything.
            3. ONLY if that second pass also returns nothing usable, state the result clearly and exactly:
               "I was not able to find any information on that topic." Never leave the outcome vague or
               trailing off.
            4. A real answer is not a failure: a well-supported conclusion — including a negative one (for
               example, that two things are unrelated) — is an answer. Reserve the not-found statement for
               when no source actually answers the request.
            5. The result of every query must be clear: the final statement is either the answer to the
               question or the exact not-found sentence above.
            6. After a not-found result, if (and only if) a specific clarification could plausibly improve the
               search — an exact name, date, location, edition, spelling, or which of several meanings — ask
               ONE short clarifying question. If no clarification would help, do not ask one.
            """;

    private final SkillStore store;

    public FailToFindSkillService(SkillStore store) {
        this.store = store;
    }

    /** Returns the skill, writing/refreshing it on disk on first use or after an upgrade. */
    public synchronized Skill ensureFailToFindSkill() {
        Skill existing = store.get(SKILL_NAME);
        boolean valid = existing != null && existing.instructions() != null
                && !existing.instructions().isBlank();
        if (valid && store.version(SKILL_NAME) == SKILL_VERSION) {
            return existing;
        }
        Skill skill = new Skill(SKILL_NAME, SKILL_DESCRIPTION, INSTRUCTIONS.strip());
        try {
            store.save(skill);
            store.setVersion(SKILL_NAME, SKILL_VERSION);
            log.info("[skills] failing-to-find-information skill {}.", valid ? "upgraded" : "created");
        } catch (RuntimeException e) {
            log.warn("[skills] could not persist failing-to-find-information skill ({}); using it in-memory",
                    e.getMessage());
        }
        return skill;
    }

    /** Convenience: the instructions text of the (ensured) skill. */
    public String failToFindInstructions() {
        return ensureFailToFindSkill().instructions();
    }
}
