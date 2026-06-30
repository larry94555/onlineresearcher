package com.example.onlineresearcher;

import java.util.List;

/**
 * Folds a batch of older conversation messages into a running summary. Implementations may call the
 * model (see {@link LlmSummarizer}) or, in tests, return a canned string.
 */
@FunctionalInterface
public interface Summarizer {

    /**
     * @param previousSummary the summary accumulated so far (may be null/blank on first compaction)
     * @param messages        the older messages being evicted from the live window
     * @return a new summary that subsumes {@code previousSummary} and {@code messages}
     */
    String summarize(String previousSummary, List<Message> messages);
}
