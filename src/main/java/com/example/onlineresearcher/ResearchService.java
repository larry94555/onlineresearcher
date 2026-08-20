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
    private final SportsScoreSkillService sportsSkillService;
    private final FailToFindSkillService failToFindSkillService;
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
    // Follow-up questions asked after a not-found result, capped like the up-front clarifications so a
    // topic that stays unfindable cannot keep asking forever.
    private int notFoundFollowUps;

    public ResearchService(ConversationMemory memory, ChatModel model, WebResearchService webResearch,
                           ResearchSkillService skillService, SportsScoreSkillService sportsSkillService,
                           FailToFindSkillService failToFindSkillService,
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
        this.sportsSkillService = sportsSkillService;
        this.failToFindSkillService = failToFindSkillService;
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
        // Resolve the working topic, folding in any clarification the user just provided.
        String topic;
        if (awaitingClarification && pendingTopic != null) {
            topic = pendingTopic + "\nAdditional detail from the user: " + userInput.strip();
        } else {
            topic = userInput.strip();
        }

        // Step 1: assemble the skill guidance for this topic — the research skill always, plus the
        // sports-score skill when the topic asks for a match score/result.
        String skill = guidanceFor(topic);

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
        if (!answer.startsWith(NOT_FOUND_MESSAGE)) {
            notFoundFollowUps = 0;   // the topic was answered; the follow-up budget starts over
        }
        return answer;
    }

    /**
     * The skill guidance applied to this topic: always the research skill, plus the sports-score skill when
     * the topic looks like a request for a match score/result. Both are injected into every step's prompt.
     */
    private String guidanceFor(String topic) {
        StringBuilder guidance = new StringBuilder(skillService.researchInstructions());
        if (sportsSkillService != null && SportsTopicDetector.isSportsScore(topic)) {
            guidance.append("\n\n## Additional skill — live sports scores\n")
                    .append(sportsSkillService.sportsScoreInstructions());
        }
        if (failToFindSkillService != null) {
            // Always applied: how to behave when a search does not find the answer.
            guidance.append("\n\n## Additional skill — when information is not found\n")
                    .append(failToFindSkillService.failToFindInstructions());
        }
        return guidance.toString();
    }

    // --- Step 3 & 4: gather, evaluate sufficiency, re-search, synthesize ----------------------------

    private String research(String topic, String skill) throws Exception {
        Map<String, WebSearchResult> gathered = new LinkedHashMap<>();
        List<String> allProviders = new ArrayList<>();
        List<String> researchLog = new ArrayList<>();
        java.util.Set<String> searched = new java.util.LinkedHashSet<>();
        String feedback = "";
        boolean sufficient = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Only run queries we haven't already issued, so a re-search that regenerates the same queries
            // doesn't repeat identical (and possibly rate-limited) requests.
            List<String> fresh = new ArrayList<>();
            for (String query : generateQueries(topic, skill, feedback)) {
                if (searched.add(query.toLowerCase(java.util.Locale.ROOT))) fresh.add(query);
            }
            if (fresh.isEmpty()) {
                researchLog.add("Attempt " + attempt + ": no new queries; stopping.");
                break;
            }
            researchLog.add("Attempt " + attempt + " queries: " + String.join(" | ", fresh));
            for (String query : fresh) {
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

        // First synthesis attempt on whatever the normal search gathered.
        if (!gathered.isEmpty()) {
            String answer = synthesize(topic, evidence(gathered.values()), skill, sufficient);
            if (!isNeedMore(answer)) {
                return answer + sourcesFooter(allProviders, sufficient);
            }
        }

        // Failing-to-find fallback: ask the model which keyless, agent-accessible sites would have this
        // information, then query those before giving up.
        List<String> sites = suggestSites(topic, skill);
        if (!sites.isEmpty()) {
            researchLog.add("Fallback: trying agent-accessible sources: " + String.join(", ", sites));
            for (String site : sites) {
                for (String query : siteQueries(topic, site)) {
                    if (!searched.add(query.toLowerCase(java.util.Locale.ROOT))) continue;
                    WebResearchService.Aggregated agg = webResearch.search(query, perProvider);
                    for (WebSearchResult result : agg.results()) {
                        gathered.putIfAbsent(keyOf(result), result);
                    }
                    for (String provider : agg.providersUsed()) {
                        if (!allProviders.contains(provider)) allProviders.add(provider);
                    }
                }
            }
            if (!gathered.isEmpty()) {
                String answer = synthesize(topic, evidence(gathered.values()), skill, false);
                if (!isNeedMore(answer)) {
                    return answer + sourcesFooter(allProviders, false);
                }
            }
        }

        // Both the normal search and the fallback failed: give a clear not-found result, plus a clarifying
        // question only when one could plausibly improve the search.
        return notFoundResponse(topic, skill);
    }

    static final String NOT_FOUND_MESSAGE = "I was not able to find any information on that topic.";

    private String sourcesFooter(List<String> providers, boolean sufficient) {
        if (providers.isEmpty()) return "";
        return "\n\n---\nSources searched via: " + String.join(", ", providers)
                + (sufficient ? "" : "\n(Note: information may be incomplete.)");
    }

    /** A synthesized answer is "not found" when the model emitted the NEED_MORE_SOURCES sentinel. */
    private static boolean isNeedMore(String answer) {
        return answer != null && answer.toUpperCase(java.util.Locale.ROOT).contains("NEED_MORE_SOURCES");
    }

    /**
     * Builds the clear not-found result, appending one clarifying question when it could help.
     *
     * <p>Asking the question also re-arms the clarification state, which {@link #handle} cleared before
     * researching. Without that, the answer the user types next would be read as a brand-new topic, and the
     * detail this question asked for would never reach the search it was meant to improve.
     */
    private String notFoundResponse(String topic, String skill) {
        if (notFoundFollowUps >= maxClarifications) return NOT_FOUND_MESSAGE;
        String question = notFoundClarifyingQuestion(topic, skill);
        if (question.isBlank()) return NOT_FOUND_MESSAGE;
        awaitingClarification = true;
        pendingTopic = topic;
        notFoundFollowUps++;
        return NOT_FOUND_MESSAGE + "\n\n" + question;
    }

    /**
     * Asks the model which openly-accessible (no key/login/payment) web sources would have this topic, so
     * the fallback can query them. Stateless and best-effort; returns an empty list on any failure.
     */
    private List<String> suggestSites(String topic, String skill) {
        String system = "List up to 4 specific web sources that (a) are openly accessible to automated "
                + "agents with NO API key, login, or payment, and (b) would likely have information on the "
                + "topic. Prefer reputable reference, official, or encyclopedic sites. Output each as a bare "
                + "domain or short site name, one per line — no numbering, no markdown, no commentary.";
        try {
            String reply = runModelStateless(system, "Topic:\n" + topic, 128, 0.2);
            List<String> sites = new ArrayList<>();
            for (String line : reply.split("\\r?\\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String site = sanitizeQuery(trimmed);
                if (!site.isBlank() && site.length() <= 60 && !containsIgnoreCase(sites, site)) {
                    sites.add(site);
                }
                if (sites.size() >= 4) break;
            }
            return sites;
        } catch (Exception e) {
            log.warn("[research] could not get fallback sources ({})", e.getMessage());
            return List.of();
        }
    }

    /** Search queries that target a suggested source (the topic keywords plus the source name). */
    private List<String> siteQueries(String topic, String site) {
        String core = stripFiller(topic);
        if (core.isBlank()) core = topic;
        core = truncate(core, 80);
        String keyword = siteKeyword(site);
        List<String> queries = new ArrayList<>();
        if (!keyword.isBlank()) {
            queries.add(truncate(core + " " + keyword, 100));
            queries.add(truncate("site:" + site + " " + core, 100));
        }
        return queries;
    }

    /** Reduces a source like "en.wikipedia.org"/"https://www.fifa.com/" to a query keyword ("wikipedia"/"fifa"). */
    static String siteKeyword(String site) {
        String s = site.toLowerCase(java.util.Locale.ROOT).strip()
                .replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        String[] parts = s.split("\\.");
        // For a domain like en.wikipedia.org, the second-level label ("wikipedia") is the useful keyword.
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return s;
    }

    /**
     * After a not-found result, asks the model for one clarifying question that could improve the search —
     * or nothing when no clarification would help. Stateless; returns "" on NONE or any failure.
     */
    private String notFoundClarifyingQuestion(String topic, String skill) {
        String system = "A search for the user's request found nothing usable. If a specific clarification "
                + "from the user could plausibly help find the answer (an exact name, date, location, "
                + "edition, spelling, or which of several meanings), output ONE short clarifying question and "
                + "nothing else. If no clarification would realistically help, output exactly: NONE.";
        try {
            String reply = runModelStateless(system, "Request:\n" + topic, 96, 0.2).strip();
            if (reply.isBlank() || reply.toUpperCase(java.util.Locale.ROOT).contains("NONE")) return "";
            String question = sanitizeQuery(reply);
            // sanitizeQuery strips a trailing '?'; restore it so it reads as a question.
            if (!question.isBlank() && !question.endsWith("?")) question = question + "?";
            return question;
        } catch (Exception e) {
            return "";
        }
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
        // Always include deterministic, decomposed queries (the bare entities and the cleaned topic). These
        // are the most reliable: for a "relation between X and Y" question they become "X", "Y", "X Y",
        // which is exactly how an encyclopedic source is found — no page is titled after a non-relationship.
        List<String> queries = new ArrayList<>(deterministicQueries(topic));

        String system = "You generate web search engine queries. Output ONLY plain search queries, one per "
                + "line: 2 to 8 keywords each, like terms typed into Google. No markdown, no headings, no "
                + "bold, no numbering, no LaTeX, no full sentences, no commentary, no trailing punctuation.\n"
                + "Example for the topic 'health benefits of green tea':\n"
                + "green tea health benefits\ngreen tea EGCG catechins\ngreen tea caffeine effects\n\n"
                + "Apply this research guidance when choosing terms:\n" + skill;
        StringBuilder user = new StringBuilder("Topic to research:\n").append(topic);
        if (feedback != null && !feedback.isBlank()) {
            user.append("\n\nThe previous search returned too little. Suggest new, differently worded "
                    + "keyword queries to fill these gaps:\n").append(feedback);
        }
        try {
            // Stateless: query generation must NOT see the running conversation, or a small model drifts
            // from listing queries into answering the question (it starts emitting prose as "queries").
            String reply = runModelStateless(system, user.toString(), 256, 0.3);
            for (String line : reply.split("\\r?\\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(">")) {
                    continue;   // skip blank lines and markdown headers/quotes outright
                }
                String q = sanitizeQuery(trimmed);
                if (isLikelyQuery(q) && !containsIgnoreCase(queries, q)) {
                    queries.add(q);
                }
            }
        } catch (Exception e) {
            log.warn("[research] query generation failed ({}); using deterministic queries", e.getMessage());
        }
        if (queries.isEmpty()) {
            queries.add(truncate(topic, 100));
        }
        return queries.size() > maxQueries ? new ArrayList<>(queries.subList(0, maxQueries)) : queries;
    }

    /**
     * Strips markdown/LaTeX decoration a chat model tends to add so the result is a plain keyword query:
     * leading list/heading markers, {@code **bold**}/{@code `code`} wrappers, and {@code \( ... \)} math.
     */
    static String sanitizeQuery(String line) {
        if (line == null) return "";
        String q = line.strip();
        q = q.replaceFirst("^[\\s>#*_\\-\\d.)\\]\\[]+", "");   // leading bullets/headers/numbering
        q = q.replace("**", "").replace("__", "").replace("`", "");
        q = q.replaceAll("\\\\[()\\[\\]]", " ");                // LaTeX \( \) \[ \]
        q = q.replaceAll("[$]", " ");                            // stray math delimiters
        q = q.replaceAll("\\s+", " ").strip();
        // Drop a trailing colon left over from a prose lead-in ("queries:").
        while (q.endsWith(":") || q.endsWith("-")) q = q.substring(0, q.length() - 1).strip();
        return q;
    }

    /** True when a sanitized line looks like a real keyword query and not a header label or a sentence. */
    static boolean isLikelyQuery(String q) {
        if (q == null) return false;
        String trimmed = q.strip();
        if (trimmed.length() < 2 || trimmed.length() > 100) return false;
        if (trimmed.endsWith(":")) return false;
        if (trimmed.contains("\\(") || trimmed.contains("\\)")) return false;
        int words = trimmed.split("\\s+").length;
        return words >= 1 && words <= 12;
    }

    /**
     * Deterministic, model-free queries derived from the topic: the cleaned topic itself, and — when the
     * topic asks about a relationship between two things ("between X and Y", "X vs Y") — each side and the
     * pair. This decomposition is what lets the agent answer "how do X and Y relate" by reading X and Y
     * separately.
     */
    static List<String> deterministicQueries(String topic) {
        List<String> queries = new ArrayList<>();
        if (topic == null || topic.isBlank()) return queries;
        String cleaned = stripFiller(topic);

        java.util.regex.Matcher between = java.util.regex.Pattern.compile(
                "(?i)\\bbetween\\s+(.+?)\\s+and\\s+(.+?)"
                        + "(?:\\s+(?:besides|other than|apart from|except|aside from)\\b.*|[?.!]|$)")
                .matcher(topic);
        java.util.regex.Matcher vs = java.util.regex.Pattern.compile("(?i)^(.+?)\\s+(?:vs\\.?|versus)\\s+(.+)$")
                .matcher(cleaned);
        String left = null;
        String right = null;
        if (between.find()) {
            left = stripFiller(between.group(1));
            right = stripFiller(between.group(2));
        } else if (vs.find()) {
            left = stripFiller(vs.group(1));
            right = stripFiller(vs.group(2));
        }
        if (left != null && right != null && !left.isBlank() && !right.isBlank()) {
            // The words the two entities share are usually the common subject they are named after
            // (e.g. "jacobsthal function" and "jacobsthal number" share "jacobsthal"). Searching that
            // surfaces the namesake/disambiguation page, which is exactly what explains a name-only relation.
            String shared = commonLeadingWords(left, right);
            if (!shared.isBlank()) addUnique(queries, truncate(shared, 100));
            addUnique(queries, truncate(left, 100));
            addUnique(queries, truncate(right, 100));
            addUnique(queries, truncate(left + " " + right, 100));
        }
        addUnique(queries, truncate(cleaned.isBlank() ? topic : cleaned, 100));
        return queries;
    }

    /** The maximal run of leading words two phrases share, case-insensitively ("foo bar"/"foo baz" -> "foo"). */
    static String commonLeadingWords(String a, String b) {
        String[] left = a.strip().split("\\s+");
        String[] right = b.strip().split("\\s+");
        StringBuilder shared = new StringBuilder();
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            if (!left[i].equalsIgnoreCase(right[i])) break;
            if (shared.length() > 0) shared.append(' ');
            shared.append(left[i]);
        }
        return shared.toString();
    }

    /** Removes leading question/filler phrasing so "is there a relation between X and Y" reduces to "X Y". */
    static String stripFiller(String text) {
        if (text == null) return "";
        String t = text.strip();
        t = t.replaceAll("(?i)^(?:is there |are there |what is |what are |tell me about |explain |"
                + "describe |how (?:do|does|are) |any )+", "");
        t = t.replaceAll("(?i)\\b(?:a |an |the )?(?:relationship|relation|connection|link|"
                + "difference|differences|comparison)\\b(?: between| of| among)?", " ");
        t = t.replaceAll("(?i)\\b(?:besides|other than|apart from|except|aside from)\\s+(?:their |the )?"
                + "name[s]?\\b", " ");
        t = t.replaceAll("(?i)\\bbesides\\b|\\bother than\\b", " ");
        t = t.replaceAll("[?.!,]", " ");
        t = t.replaceAll("\\s+", " ").strip();
        return t;
    }

    private Sufficiency assessSufficiency(String topic, String evidence, String skill) {
        String system = "You judge whether the gathered web evidence is sufficient to answer the topic with "
                + "basic, relevant, fact-checked details. Apply this research skill:\n" + skill + "\n\n"
                + "If the evidence covers the basics from corroborating sources, respond with exactly: "
                + "SUFFICIENT\n"
                + "IMPORTANT: if the topic asks whether two things are related, separate definitions of each "
                + "thing do NOT settle the question. A search that did not surface a relationship is not "
                + "evidence that no relationship exists. The evidence is SUFFICIENT only when some source "
                + "actually addresses the relationship — either stating a connection, or stating that the two "
                + "are distinct (a disambiguation or namesake page does this). If the evidence only defines "
                + "each thing separately, respond INSUFFICIENT and say that no source addresses whether the "
                + "two are related.\n"
                + "Otherwise respond with exactly: INSUFFICIENT: <what is missing>\n"
                + "Respond with only one line in that format.";
        String user = "Topic:\n" + topic + "\n\nGathered evidence:\n" + evidence;
        String reply;
        try {
            reply = runModelStateless(system, user, 200, 0.0).strip();
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
                + "Answer ONLY the current request below, using ONLY the gathered web evidence below. The "
                + "evidence is the sole source of truth: do NOT use prior knowledge, and do NOT bring in any "
                + "earlier, unrelated topic. If the evidence contains NOTHING that answers the request (for "
                + "example, a live sports score that none of the sources report), output EXACTLY the single "
                + "token NEED_MORE_SOURCES and nothing else — do NOT substitute an answer to a different "
                + "question. (A conclusion the evidence supports, INCLUDING a negative one that a source "
                + "actually states — such as a disambiguation page saying two things share only a name — IS a "
                + "real answer; do NOT output NEED_MORE_SOURCES in that case.)\n"
                + "Lead with the key findings, corroborate facts across sources where possible, note any "
                + "uncertainty or disagreement, and do not invent specific facts not supported by the "
                + "evidence. Be concise — a few short paragraphs at most. NEVER reproduce long numeric "
                + "sequences or raw data dumps; cite at most the first few terms.\n"
                + "CRITICAL for 'how are X and Y related' questions: do NOT invent a connection to fill a "
                + "gap, and do NOT rule one out either. Give the definitions the sources support, then say "
                + "precisely what the sources do and do not establish about the relationship. If no source "
                + "addresses a connection, write that none of the sources searched describes one — and that "
                + "this leaves the question unresolved, NOT that the two are unrelated. Report them as "
                + "unrelated, or related only by a shared name, ONLY when a source says so. Do not claim they "
                + "are 'both related to' some third thing unless a source explicitly says so.\n"
                + "End with a 'Sources' list of the titles and URLs you used.";
        if (!sufficient) {
            // The gathering loop gave up short of what it wanted. An unfinished search is exactly where an
            // unsupported conclusion gets written, so the answer has to show its seams.
            system += "\nThe evidence was judged INCOMPLETE for this request. Name plainly which "
                    + "part of the request the sources do not settle, and present nothing beyond them as a "
                    + "conclusion.";
        }
        String user = "Current request:\n" + topic + "\n\nGathered web evidence:\n" + evidence;
        // Stateless: the answer must be grounded in THIS request's evidence only. Routing synthesis through
        // conversation memory let the model continue a previous, unrelated topic when the current evidence
        // was thin (it answered a new World Cup question with a prior Jacobsthal answer).
        return runModelStateless(system, user, responseReserve, 0.3).strip();
    }

    /**
     * Routes a model call through conversation memory so prior turns are included and the assembled
     * request stays within the token budget. Does NOT record the exchange (internal step).
     */
    private String runModel(String system, String user, int maxTokens, double temperature) throws Exception {
        List<Message> toSend = memory.prepareRequest(system, user, Math.max(maxTokens, responseReserve));
        return model.chat(toSend, maxTokens, temperature);
    }

    /**
     * A model call that deliberately does NOT include conversation memory — only the given system and user
     * messages. Used for internal utility steps (query generation, sufficiency) where a small model would
     * otherwise drift from following the instruction into continuing the conversation.
     */
    private String runModelStateless(String system, String user, int maxTokens, double temperature)
            throws Exception {
        return model.chat(List.of(Message.system(system), Message.user(user)), maxTokens, temperature);
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private static void addUnique(List<String> values, String candidate) {
        if (candidate != null && !candidate.isBlank() && !containsIgnoreCase(values, candidate)) {
            values.add(candidate);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max).strip();
    }

    /** Builds a compact, citation-friendly evidence block, trimmed to the configured character budget. */
    private String evidence(java.util.Collection<WebSearchResult> results) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (WebSearchResult result : results) {
            // Cap each source so no single long extract/sequence dominates the evidence the model sees.
            String entry = "[" + index + "] " + safe(result.title()) + "\n    URL: " + safe(result.url())
                    + "\n    " + truncate(safe(result.snippet()), 700) + "\n";
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
