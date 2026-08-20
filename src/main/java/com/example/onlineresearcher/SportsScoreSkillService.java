package com.example.onlineresearcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the {@code sports-score} skill: strategy for answering questions about the latest score/result of a
 * sports match. Unlike the {@code research} skill (bootstrapped from the web), this skill's content is
 * built in — it encodes the data sources that are accessible to agents for live scores (real-time sports
 * data APIs, live news/RSS feeds, search-engine score panels, and sportsbook/betting feeds) so the agent
 * knows where live scores actually live, prefers those sources, and never fabricates a scoreline.
 *
 * <p>It is applied (in addition to the research skill) only when the topic looks like a sports-score
 * question — see {@link SportsTopicDetector}. The skill is persisted and versioned through {@link SkillStore}
 * so an upgrade refreshes a saved copy automatically.
 */
@Component
public class SportsScoreSkillService {
    private static final Logger log = LoggerFactory.getLogger(SportsScoreSkillService.class);

    static final String SKILL_NAME = "sports-score";
    static final int SKILL_VERSION = 1;
    private static final String SKILL_DESCRIPTION =
            "How to find the latest score/result of a sports match from sources accessible to agents.";

    static final String INSTRUCTIONS = """
            Use this strategy when the user asks for the latest score, result, or who won a sports match.
            Live scores are time-sensitive and are NOT reliably found in encyclopedic sources, so target the
            sources below — and never guess or invent a scoreline.

            1. Prefer real-time, authoritative live-score sources, in roughly this order:
               a. Live sports data APIs (e.g. Sportradar, SportMonks, API-Football, TheSportsDB) — these give
                  structured live match events (goals, cards, exact minute). Use them when an API key / tool
                  is configured.
               b. Real-time news feeds and live blogs from major sports publishers (e.g. ESPN, The Athletic,
                  BBC Sport, official league/tournament sites) — parse their live-update or RSS endpoints.
               c. Search-engine result boxes / scorecard panels — a query like "<team A> <team B> score" or
                  "<team A> vs <team B> <date>" often surfaces a live score panel; extract it.
               d. Sportsbook / betting feeds (e.g. DraftKings, FanDuel, authorized odds APIs) — their in-play
                  odds and scoreboards update instantly during a match and corroborate the live score.
            2. Identify the exact match first: the two teams/competitors, the competition, and the date
               ("today", a specific date, or "most recent"). If any of these is ambiguous, ask before searching.
            3. Report a score ONLY if a source actually states it. Always include the source and the time the
               score was reported, and whether the match is final, in progress (with the minute), or upcoming.
            4. Cross-check the score across at least two independent sources when possible; note any
               disagreement (e.g. a betting feed lagging the official feed).
            5. Beware staleness: a cached or encyclopedic page may show an old or pre-match state. Prefer the
               most recently updated source and say how fresh it is.
            6. If none of the available sources report the score (for example, no live-data API or sportsbook
               feed is configured and search returned no score panel), say plainly that you could not retrieve
               a live score, and point the user to the authoritative live sources above to check directly.
               Do NOT substitute an estimate, a pre-match prediction, or an unrelated answer.
            """;

    private final SkillStore store;

    public SportsScoreSkillService(SkillStore store) {
        this.store = store;
    }

    /** Returns the sports-score skill, writing/refreshing it on disk on first use or after an upgrade. */
    public synchronized Skill ensureSportsScoreSkill() {
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
            log.info("[skills] sports-score skill {}.", valid ? "upgraded" : "created");
        } catch (RuntimeException e) {
            log.warn("[skills] could not persist sports-score skill ({}); using it in-memory", e.getMessage());
        }
        return skill;
    }

    /** Convenience: the instructions text of the (ensured) sports-score skill. */
    public String sportsScoreInstructions() {
        return ensureSportsScoreSkill().instructions();
    }
}
