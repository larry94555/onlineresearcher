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

    /** Bumping this rebuilds any saved skill on next use, so existing installs pick up new strategy. */
    static final int SKILL_VERSION = 4;

    /** Used when the model is asked to turn raw best-practice snippets into a skill. */
    static final String SYNTHESIS_SYSTEM = """
            You are writing a reusable "research" skill: concise, durable guidance an AI agent will follow
            every time it researches a topic on the web. Using the web snippets provided (and sound general
            knowledge of research methodology), write the skill as a numbered list of imperative best
            practices. Cover at least: forming focused search queries; identifying the authoritative and
            reputable sources for the specific topic; preferring those authoritative sources over less
            reliable sites; checking facts against those authoritative sources; using multiple independent
            sources; cross-checking and corroborating facts; watching for bias, dates, and outdated
            information; distinguishing facts from opinion; treating a search that surfaced no connection
            between two subjects as unresolved rather than as proof that no connection exists; and citing
            sources with their URLs. Output only the guidance itself — no preamble, no closing remarks.
            """;

    /**
     * Appended to every research skill, however it was built. The model-synthesized guidance is written
     * from web snippets and cannot be relied on to state this — and guidance that omits it is injected into
     * every step of the flow, where it becomes the false negative the synthesis prompts are written to
     * prevent. Kept here once, so the built-in default and the synthesized text carry the identical rule.
     */
    static final String REQUIRED_POLICY = """
            Non-negotiable rules — these hold whatever else this guidance says:
            - Do not turn a failed search into a finding. If no source addresses whether two subjects are
              related, report the relationship as unresolved: none of the sources searched describes a
              connection, which is not the same as establishing that none exists.
            - Conclude that two subjects are unrelated, or related only by a shared name, only when a source
              actually says so (a disambiguation or namesake page does).
            """;

    /** Built-in fallback so the agent always has research guidance even with no network/model. */
    static final String DEFAULT_INSTRUCTIONS = """
            1. Break the topic into focused, specific keyword queries (2-8 words); vary the wording across
               attempts. Search engine queries are keywords, not sentences or markdown.
            2. When the question is about how two things relate ("relation between X and Y", "X vs Y"),
               research EACH thing separately first ("X", "Y") and, if relevant, the person or origin they
               are named after. The link (or lack of one) emerges from understanding each side.
            3. Identify the authoritative and reputable sources for THIS topic before trusting general
               results: e.g. official sites and standards bodies, encyclopedias (Wikipedia), domain
               references (OEIS for integer sequences, peer-reviewed journals for science/medicine, official
               documentation for software/products), and recognized experts. Note which sources those are.
            4. Prefer those authoritative sources over blogs, forums, SEO/marketing pages, and AI-generated
               content when they cover the topic; give their facts more weight when sources disagree.
            5. Check every important fact AGAINST those authoritative sources; corroborate across at least
               two independent sources before trusting it, and note the publication date of each claim.
            6. Gather from several independent sources rather than relying on a single page.
            7. Watch for bias, marketing language, and conflicts of interest; separate fact from opinion.
            8. Flag uncertainty and disagreement between sources instead of papering over it.
            9. Distinguish what the sources actually state from your own inference.
            10. Cite the sources you used, with their URLs, so the reader can verify the findings.
            11. If the gathered information is thin or contradictory, refine the queries and search again.
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
        boolean valid = existing != null && existing.instructions() != null
                && !existing.instructions().isBlank();
        if (valid && store.version(SKILL_NAME) == SKILL_VERSION) {
            return existing;
        }
        if (valid) {
            log.info("[skills] research skill is from an older version (v{} != v{}); rebuilding it so the "
                    + "latest strategy is applied...", store.version(SKILL_NAME), SKILL_VERSION);
        } else {
            log.info("[skills] research skill not found; bootstrapping it from web best practices...");
        }
        String instructions = buildInstructions();
        Skill skill = new Skill(SKILL_NAME, SKILL_DESCRIPTION, instructions);
        try {
            store.save(skill);
            store.setVersion(SKILL_NAME, SKILL_VERSION);
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
            return withRequiredPolicy(DEFAULT_INSTRUCTIONS);
        }
        if (found.isEmpty()) {
            log.info("[skills] no web results for best practices; using built-in research guidance");
            return withRequiredPolicy(DEFAULT_INSTRUCTIONS);
        }
        String evidence = formatEvidence(found.results());
        try {
            String synthesized = model.chat(
                    List.of(Message.system(SYNTHESIS_SYSTEM),
                            Message.user("Web snippets on research best practices:\n\n" + evidence)),
                    summaryMaxTokens, 0.2).strip();
            return withRequiredPolicy(synthesized.isBlank() ? DEFAULT_INSTRUCTIONS : synthesized);
        } catch (Exception e) {
            log.warn("[skills] model synthesis of research skill failed: {}", e.getMessage());
            return withRequiredPolicy(DEFAULT_INSTRUCTIONS);
        }
    }

    /**
     * Guidance plus the non-negotiable rules. Every path through {@link #buildInstructions} ends here, so
     * no skill — built in, synthesized, or rebuilt — can ship without them.
     */
    static String withRequiredPolicy(String instructions) {
        String guidance = instructions == null || instructions.isBlank()
                ? DEFAULT_INSTRUCTIONS.strip() : instructions.strip();
        return guidance + "\n\n" + REQUIRED_POLICY.strip();
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
