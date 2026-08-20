package com.example.onlineresearcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default {@link Summarizer}: asks llama-server to condense earlier turns into a compact summary. The
 * instruction mirrors how Claude Code auto-compacts a long session — it asks the model to preserve the
 * facts, decisions, and intent needed to keep the conversation coherent, while dropping verbatim detail.
 *
 * <p>Summarization is best-effort: if the model call fails, the previous summary is retained (the evicted
 * turns are lost rather than failing the user's request), keeping the token-budget invariant intact.
 */
@Component
public class LlmSummarizer implements Summarizer {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmSummarizer.class);

    static final String SUMMARY_SYSTEM_PROMPT = """
            You are compacting a research conversation so it fits within a limited context window.
            Write a concise summary that captures everything needed to continue the conversation seamlessly:
            - the topics the user asked to research and any conclusions or findings reached
            - key facts, names, numbers, sources, and constraints that were established
            - any clarifications the user gave about what they wanted
            Integrate the prior summary (if any) with the new messages into a single, self-contained summary.
            Do not add commentary, greetings, or meta-text such as "Here is the summary". Output only the summary.
            """;

    private final LlamaClient llama;
    private final int summaryMaxTokens;

    public LlmSummarizer(LlamaClient llama,
                         @Value("${memory.summary-max-tokens:1024}") int summaryMaxTokens) {
        this.llama = llama;
        this.summaryMaxTokens = summaryMaxTokens;
    }

    @Override
    public String summarize(String previousSummary, List<Message> messages) {
        StringBuilder transcript = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            transcript.append("Summary so far:\n").append(previousSummary).append("\n\n");
        }
        transcript.append("New messages to fold into the summary:\n");
        for (Message message : messages) {
            transcript.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        try {
            return llama.ask(SUMMARY_SYSTEM_PROMPT, transcript.toString(), summaryMaxTokens, 0.0).trim();
        } catch (Exception e) {
            log.warn("[memory] summarization failed ({}); keeping previous summary", e.getMessage());
            return previousSummary == null ? "" : previousSummary;
        }
    }
}
