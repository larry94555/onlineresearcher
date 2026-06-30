package com.example.onlineresearcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Owns the {@code research} skill. On first use it bootstraps the skill the way the task describes: it
 * searches the web for best practices on doing research and fact-checking, asks the model to synthesize
 * those into a reusable skill, and saves it to the {@link SkillStore}. On later runs it just loads the
 * saved skill. If the web or model is unavailable when bootstrapping, a sensible built-in default is used
 * so the agent is never left without research guidance.
 */
@Component
public class ResearchSkillService {
    private static final Logger log = LoggerFactory.getLogger(ResearchSkillService.class);

    static final String SKILL_NAME = "research";
    private static final String SKILL_DESCRIPTION =
            "How to research a topic on the web and fact-check findings across multiple sources.";

    private static final String BOOTSTRAP_QUERY =
            "best practices for doing research on the web and fact checking sources";

    /** Used when the model is asked to turn raw best-practice snippets into a skill. */
    private static final String SYNTHESIS_SYSTEM = """
            You are writing a reusable "research" skill: concise, durable guidance an AI agent will follow
            every time it researches a topic on the web. Using the web snippets provided (and sound general
            knowledge of research methodology), write the skill as a numbered list of imperative best
            practices. Cover at least: forming focused search queries; using multiple independent sources;
            preferring primary and authoritative sources; cross-checking and corroborating facts; watching
            for bias, dates, and outdated information; distinguishing facts from opinion; and citing sources
            with their URLs. Output only the guidance itself — no preamble, no closing remarks.
            """;

    /** Built-in fallback so the agent always has research guidance even with no network/model. */
    static final String DEFAULT_INSTRUCTIONS = """
            1. Break the topic into focused, specific search queries; vary the wording across attempts.
            2. Gather from several independent sources rather than relying on a single page.
            3. Prefer primary, authoritative, and recent sources; note the publication date of each claim.
            4. Corroborate every important fact across at least two independent sources before trusting it.
            5. Watch for bias, marketing language, and conflicts of interest; separate fact from opinion.
            6. Flag uncertainty and disagreement between sources instead of papering over it.
            7. Distinguish what the sources actually state from your own inference.
            8. Cite the sources you used, with their URLs, so the reader can verify the findings.
            9. If the gathered information is thin or contradictory, refine the queries and search again.
            """;

    private final SkillStore store;
    private final WebResearchService webResearch;
    private final ChatModel model;
    private final int summaryMaxTokens;

    public ResearchSkillService(SkillStore store, WebResearchService webResearch, ChatModel model) {
        this.store = store;
        this.webResearch = webResearch;
        this.model = model;
        this.summaryMaxTokens = 1024;
    }

    /**
     * Returns the research skill, creating and saving it on first use. Idempotent and safe to call before
     * every research turn (step 1 of the flow).
     */
    public synchronized Skill ensureResearchSkill() {
        Skill existing = store.get(SKILL_NAME);
        if (existing != null && existing.instructions() != null && !existing.instructions().isBlank()) {
            return existing;
        }
        log.info("[skills] research skill not found; bootstrapping it from web best practices...");
        String instructions = buildInstructions();
        Skill skill = new Skill(SKILL_NAME, SKILL_DESCRIPTION, instructions);
        try {
            store.save(skill);
        } catch (RuntimeException e) {
            log.warn("[skills] could not persist research skill ({}); using it in-memory for this run",
                    e.getMessage());
        }
        return skill;
    }

    /** Convenience: the instructions text of the (ensured) research skill. */
    public String researchInstructions() {
        return ensureResearchSkill().instructions();
    }

    private String buildInstructions() {
        WebResearchService.Aggregated found;
        try {
            found = webResearch.search(BOOTSTRAP_QUERY, 6);
        } catch (RuntimeException e) {
            log.warn("[skills] web search for best practices failed: {}", e.getMessage());
            return DEFAULT_INSTRUCTIONS.strip();
        }
        if (found.isEmpty()) {
            log.info("[skills] no web results for best practices; using built-in research guidance");
            return DEFAULT_INSTRUCTIONS.strip();
        }
        String evidence = formatEvidence(found.results());
        try {
            String synthesized = model.chat(
                    List.of(Message.system(SYNTHESIS_SYSTEM),
                            Message.user("Web snippets on research best practices:\n\n" + evidence)),
                    summaryMaxTokens, 0.2).strip();
            return synthesized.isBlank() ? DEFAULT_INSTRUCTIONS.strip() : synthesized;
        } catch (Exception e) {
            log.warn("[skills] model synthesis of research skill failed: {}", e.getMessage());
            return DEFAULT_INSTRUCTIONS.strip();
        }
    }

    private static String formatEvidence(List<WebSearchResult> results) {
        StringBuilder builder = new StringBuilder();
        for (WebSearchResult result : results) {
            builder.append("- ").append(result.title());
            if (result.snippet() != null && !result.snippet().isBlank()) {
                builder.append(": ").append(result.snippet());
            }
            if (result.url() != null && !result.url().isBlank()) {
                builder.append(" (").append(result.url()).append(')');
            }
            builder.append('\n');
        }
        return builder.toString().strip();
    }
}
