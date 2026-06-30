package com.example.onlineresearcher;

import java.util.List;

/**
 * Minimal seam over the chat model so the research orchestration can be unit-tested without a live
 * llama-server. The production implementation ({@link LlamaChatModel}) forwards to {@link LlamaClient}; a
 * test can supply a lambda that returns canned replies.
 */
@FunctionalInterface
public interface ChatModel {

    /** Sends an assembled message list to the model and returns the assistant's text reply. */
    String chat(List<Message> messages, Integer maxTokens, Double temperature) throws Exception;
}
