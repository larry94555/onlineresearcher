package com.example.onlineresearcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The conversation memory for a single session. It keeps prior user/assistant turns so each prompt has
 * the context of the ones before it, and compacts that history — the way Claude Code auto-compacts — so
 * the assembled request always fits within the token budget. Kept identical to roleflow.
 *
 * <p><b>Compaction.</b> Before each request the memory checks whether
 * {@code system + running-summary + turns + new prompt} fits within
 * {@code memory.max-tokens - response-reserve}. If not, the oldest turns are evicted (oldest first, so the
 * most recent turns are always kept verbatim) and folded into a running summary via the {@link Summarizer}.
 * This guarantees the invariant the task requires: <em>memory + the user prompt are always smaller than
 * {@code memory.max-tokens} (8100 here)</em> (provided the prompt itself fits; an oversized prompt simply
 * clears memory).
 *
 * <p>A fresh memory is created per application start, so restarting the app starts a new session.
 * Access is synchronized because separate readers may share one session.
 */
@Component
public class ConversationMemory {

    private final TokenEstimator estimator;
    private final Summarizer summarizer;
    private final int maxTokens;

    private final List<Message> turns = new ArrayList<>();
    private String summary = "";

    public ConversationMemory(TokenEstimator estimator, Summarizer summarizer,
                              @Value("${memory.max-tokens:8100}") int maxTokens) {
        this.estimator = estimator;
        this.summarizer = summarizer;
        this.maxTokens = maxTokens;
    }

    /**
     * Builds the message list to send for this prompt, compacting older history if needed so the result
     * fits within {@code maxTokens - responseReserve}. May mutate the running summary and drop old turns.
     *
     * @param systemPrompt    optional system prompt (may be null/blank)
     * @param userPrompt      the new user prompt
     * @param responseReserve tokens to reserve for the model's reply
     */
    public synchronized List<Message> prepareRequest(String systemPrompt, String userPrompt, int responseReserve) {
        int budget = Math.max(1, maxTokens - Math.max(0, responseReserve));

        // Fast path: everything already fits, so no summarization (and no model call) is needed.
        if (fits(systemPrompt, userPrompt, budget)) {
            return assemble(systemPrompt, userPrompt);
        }

        // Evict the oldest turns until the remaining window fits, then fold them into the summary in a
        // single pass. Oldest-first eviction keeps the most recent turns verbatim.
        List<Message> evicted = new ArrayList<>();
        while (!turns.isEmpty() && !fits(systemPrompt, userPrompt, budget)) {
            evicted.add(turns.remove(0));
        }
        if (!evicted.isEmpty()) {
            summary = summarizer.summarize(summary, evicted);
        }

        // If folding produced a summary that is itself too large, keep evicting and re-summarizing.
        while (!turns.isEmpty() && !fits(systemPrompt, userPrompt, budget)) {
            summary = summarizer.summarize(summary, List.of(turns.remove(0)));
        }

        // Last resort: with no turns left, if the summary still pushes us over budget, drop it. Whatever
        // remains (system + prompt) is sent best-effort.
        if (!fits(systemPrompt, userPrompt, budget)) {
            summary = "";
        }
        return assemble(systemPrompt, userPrompt);
    }

    /** Records a completed exchange so it provides context for the next prompt. */
    public synchronized void recordExchange(String userPrompt, String assistantReply) {
        turns.add(Message.user(userPrompt));
        turns.add(Message.assistant(assistantReply));
    }

    /** Current running summary of compacted history (empty when nothing has been compacted yet). */
    public synchronized String summary() {
        return summary;
    }

    /** A snapshot of the retained (non-compacted) turns. */
    public synchronized List<Message> turns() {
        return new ArrayList<>(turns);
    }

    /** Clears the session (summary and retained turns). */
    public synchronized void clear() {
        turns.clear();
        summary = "";
    }

    private boolean fits(String systemPrompt, String userPrompt, int budget) {
        return estimator.estimate(assemble(systemPrompt, userPrompt)) <= budget;
    }

    private List<Message> assemble(String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        String system = combineSystem(systemPrompt, summary);
        if (!system.isBlank()) {
            messages.add(Message.system(system));
        }
        messages.addAll(turns);
        messages.add(Message.user(userPrompt));
        return messages;
    }

    private static String combineSystem(String systemPrompt, String summary) {
        StringBuilder builder = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.append(systemPrompt.trim());
        }
        if (summary != null && !summary.isBlank()) {
            if (builder.length() > 0) builder.append("\n\n");
            builder.append("Summary of earlier conversation:\n").append(summary.trim());
        }
        return builder.toString();
    }
}
