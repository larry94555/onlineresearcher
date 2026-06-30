package com.example.onlineresearcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The research orchestrator — the full flow the task describes, run for every terminal prompt:
 *
 * <ol>
 *   <li>Ensure the {@code research} skill exists (create it from web best practices if not).</li>
 *   <li>Read the user's prompt. If it is not a clear, researchable topic/question, ask clarifying
 *       questions and wait for the answer (repeating until it is clear).</li>
 *   <li>Use the research skill to search the keyless web providers for the topic.</li>
 *   <li>Evaluate whether the gathered information is sufficient for basic, relevant details. If it is,
 *       synthesize and return the answer; if not, feed the gaps back and search again (bounded).</li>
 * </ol>
 *
 * <p>Every model call is routed through {@link ConversationMemory#prepareRequest} so prior prompts and
 * answers inform each step <em>and</em> the assembled request (memory + the user prompt) is always kept
 * below the {@code memory.max-tokens} budget (8100). Only the final prompt→answer exchange of a turn is
 * recorded into memory, so the many internal model calls don't pollute the history.
 */
@Service
public class ResearchService {
    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);

    private final ConversationMemory memory;
    private final ChatModel model;
    private final WebResearchService webResearch;
    private final ResearchSkillService skillService;
    private final int responseReserve;
    private final int maxQueries;
    private final int maxAttempts;
    private final int maxEvidenceChars;
    private final int perProvider;
    private final int maxClarifications;

    // Clarification state for the running session.
    private boolean awaitingClarification;
    private String pendingTopic;
    private int clarificationCount;

    public ResearchService(ConversationMemory memory, ChatModel model, WebResearchService webResearch,
                           ResearchSkillService skillService,
                           @Value("${prompt.max-tokens:1024}") int responseReserve,
                           @Value("${research.max-queries:4}") int maxQueries,
                           @Value("${research.max-attempts:3}") int maxAttempts,
                           @Value("${research.max-evidence-chars:16000}") int maxEvidenceChars,
                           @Value("${web.search.max-results:5}") int perProvider,
                           @Value("${research.max-clarifications:3}") int maxClarifications) {
        this.memory = memory;
        this.model = model;
        this.webResearch = webResearch;
        this.skillService = skillService;
        this.responseReserve = responseReserve;
        this.maxQueries = Math.max(1, maxQueries);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxEvidenceChars = Math.max(1000, maxEvidenceChars);
        this.perProvider = Math.max(1, perProvider);
        this.maxClarifications = Math.max(0, maxClarifications);
    }

    /** True while the agent is waiting for the user to answer a clarifying question. */
    public synchronized boolean awaitingReply() {
        return awaitingClarification;
    }

    /**
     * Handles one line of terminal input: either a new research topic or the answer to a pending
     * clarifying question. Returns the text to print — a clarifying question or the researched answer.
     */
    public synchronized String handle(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "Please enter a topic or question to research.";
        }
        // Step 1: the research skill must exist before we research.
        String skill = skillService.researchInstructions();

        // Resolve the working topic, folding in any clarification the user just provided.
        String topic;
        if (awaitingClarification && pendingTopic != null) {
            topic = pendingTopic + "\nAdditional detail from the user: " + userInput.strip();
        } else {
            topic = userInput.strip();
        }

        // Step 2: is the topic clear enough to research?
        Clarity clarity = assessClarity(topic, skill);
        if (!clarity.clear() && clarificationCount < maxClarifications) {
            awaitingClarification = true;
            pendingTopic = topic;
            clarificationCount++;
            String question = clarity.question().isBlank()
                    ? "Could you clarify exactly what you'd like me to research about this?"
                    : clarity.question();
            memory.recordExchange(userInput, question);
            return question;
        }

        // Clear (or we've asked enough): research it.
        awaitingClarification = false;
        pendingTopic = null;
        clarificationCount = 0;

        String answer;
        try {
            answer = research(topic, skill);
        } catch (Exception e) {
            log.warn("[research] failed: {}", e.getMessage());
            answer = "Sorry — research failed: " + e.getMessage();
        }
        memory.recordExchange(userInput, answer);
        return answer;
    }

    // --- Step 3 & 4: gather, evaluate sufficiency, re-search, synthesize ----------------------------

    private String research(String topic, String skill) throws Exception {
        Map<String, WebSearchResult> gathered = new LinkedHashMap<>();
        List<String> allProviders = new ArrayList<>();
        List<String> researchLog = new ArrayList<>();
        String feedback = "";
        boolean sufficient = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<String> queries = generateQueries(topic, skill, feedback);
            researchLog.add("Attempt " + attempt + " queries: " + String.join(" | ", queries));
            for (String query : queries) {
                WebResearchService.Aggregated agg = webResearch.search(query, perProvider);
                for (WebSearchResult result : agg.results()) {
                    gathered.putIfAbsent(keyOf(result), result);
                }
                for (String provider : agg.providersUsed()) {
                    if (!allProviders.contains(provider)) allProviders.add(provider);
                }
            }
            researchLog.add("After attempt " + attempt + ": " + gathered.size() + " unique source(s)");

            if (gathered.isEmpty()) {
                feedback = "No results were returned. Try broader or differently worded queries.";
                continue;
            }
            // Step 4: sufficiency check.
            Sufficiency check = assessSufficiency(topic, evidence(gathered.values()), skill);
            if (check.sufficient()) {
                sufficient = true;
                break;
            }
            feedback = check.gaps();
            researchLog.add("Attempt " + attempt + " judged insufficient: " + feedback);
        }

        if (gathered.isEmpty()) {
            return "I couldn't retrieve any web results for this topic. The keyless search providers may be "
                    + "rate-limited or unreachable right now. Enabled providers: "
                    + String.join(", ", webResearch.enabledProviders()) + ".\n\nResearch log:\n- "
                    + String.join("\n- ", researchLog);
        }

        String report = synthesize(topic, evidence(gathered.values()), skill, sufficient);
        return report + "\n\n---\nSources searched via: " + String.join(", ", allProviders)
                + (sufficient ? "" : "\n(Note: information may be incomplete after " + maxAttempts
                        + " research attempt(s).)");
    }

    // --- Model-backed steps ------------------------------------------------------------------------

    record Clarity(boolean clear, String question) {}

    record Sufficiency(boolean sufficient, String gaps) {}

    private Clarity assessClarity(String topic, String skill) {
        String system = "You decide whether a user's request is a clear, researchable topic or question. "
                + "Apply this research skill when judging clarity:\n" + skill + "\n\n"
                + "If the request is specific enough to start web research, respond with exactly: CLEAR\n"
                + "If it is too vague, ambiguous, or missing essential scope, respond with exactly: "
                + "UNCLEAR: <one short clarifying question>\n"
                + "Respond with only one line in that format.";
        String reply;
        try {
            reply = runModel(system, "Request to evaluate:\n" + topic, 200, 0.0).strip();
        } catch (Exception e) {
            // If we can't evaluate clarity, assume it's clear and proceed rather than block the user.
            return new Clarity(true, "");
        }
        String upper = reply.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("CLEAR")) {
            return new Clarity(true, "");
        }
        if (upper.startsWith("UNCLEAR")) {
            int colon = reply.indexOf(':');
            String question = colon >= 0 ? reply.substring(colon + 1).strip() : "";
            return new Clarity(false, question);
        }
        // Unrecognized format: treat a question-like reply as a clarification, otherwise proceed.
        return reply.contains("?") ? new Clarity(false, reply) : new Clarity(true, "");
    }

    private List<String> generateQueries(String topic, String skill, String feedback) {
        StringBuilder system = new StringBuilder();
        system.append("You generate web search queries to research a topic. Apply this research skill:\n")
                .append(skill).append("\n\n")
                .append("Output up to ").append(maxQueries)
                .append(" focused search queries, one per line, no numbering, no commentary.");
        StringBuilder user = new StringBuilder("Topic to research:\n").append(topic);
        if (feedback != null && !feedback.isBlank()) {
            user.append("\n\nThe previous search was insufficient. Address these gaps with new, more "
                    + "specific queries:\n").append(feedback);
        }
        try {
            String reply = runModel(system.toString(), user.toString(), 256, 0.3);
            List<String> queries = new ArrayList<>();
            for (String line : reply.split("\\r?\\n")) {
                String q = line.replaceFirst("^\\s*(?:[-*\\d.)]+)\\s*", "").trim();
                if (!q.isBlank() && !queries.contains(q)) queries.add(q);
                if (queries.size() >= maxQueries) break;
            }
            if (!queries.isEmpty()) return queries;
        } catch (Exception e) {
            log.warn("[research] query generation failed ({}); falling back to the raw topic", e.getMessage());
        }
        return List.of(topic.length() > 256 ? topic.substring(0, 256) : topic);
    }

    private Sufficiency assessSufficiency(String topic, String evidence, String skill) {
        String system = "You judge whether the gathered web evidence is sufficient to answer the topic with "
                + "basic, relevant, fact-checked details. Apply this research skill:\n" + skill + "\n\n"
                + "If the evidence covers the basics from corroborating sources, respond with exactly: "
                + "SUFFICIENT\nOtherwise respond with exactly: INSUFFICIENT: <what is missing>\n"
                + "Respond with only one line in that format.";
        String user = "Topic:\n" + topic + "\n\nGathered evidence:\n" + evidence;
        String reply;
        try {
            reply = runModel(system, user, 200, 0.0).strip();
        } catch (Exception e) {
            // If we can't evaluate, accept what we have rather than loop forever.
            return new Sufficiency(true, "");
        }
        String upper = reply.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("SUFFICIENT")) {
            return new Sufficiency(true, "");
        }
        if (upper.startsWith("INSUFFICIENT")) {
            int colon = reply.indexOf(':');
            return new Sufficiency(false, colon >= 0 ? reply.substring(colon + 1).strip() : "");
        }
        return new Sufficiency(true, "");
    }

    private String synthesize(String topic, String evidence, String skill, boolean sufficient) throws Exception {
        String system = "You are a careful research assistant. Apply this research skill while writing:\n"
                + skill + "\n\n"
                + "Using ONLY the gathered web evidence below, write a clear, well-organized answer about the "
                + "topic. Lead with the key findings, corroborate facts across sources where possible, note "
                + "any uncertainty or disagreement between sources, and do not invent facts that are not in "
                + "the evidence. End with a 'Sources' list of the titles and URLs you used.";
        String user = "Topic:\n" + topic + "\n\nGathered web evidence:\n" + evidence;
        return runModel(system, user, responseReserve, 0.3).strip();
    }

    /**
     * Routes a model call through conversation memory so prior turns are included and the assembled
     * request stays within the token budget. Does NOT record the exchange (internal step).
     */
    private String runModel(String system, String user, int maxTokens, double temperature) throws Exception {
        List<Message> toSend = memory.prepareRequest(system, user, Math.max(maxTokens, responseReserve));
        return model.chat(toSend, maxTokens, temperature);
    }

    /** Builds a compact, citation-friendly evidence block, trimmed to the configured character budget. */
    private String evidence(java.util.Collection<WebSearchResult> results) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (WebSearchResult result : results) {
            String entry = "[" + index + "] " + safe(result.title()) + "\n    URL: " + safe(result.url())
                    + "\n    " + safe(result.snippet()) + "\n";
            if (builder.length() + entry.length() > maxEvidenceChars) break;
            builder.append(entry);
            index++;
        }
        return builder.toString().strip();
    }

    private static String keyOf(WebSearchResult result) {
        String url = result.url() == null ? "" : result.url().trim();
        return url.isBlank() ? (result.title() == null ? "" : result.title().trim()) : url;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
