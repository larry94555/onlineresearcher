package com.example.onlineresearcher;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Production {@link ChatModel}: adapts {@link Message} lists to the wire shape {@link LlamaClient} expects
 * and forwards to llama-server's {@code /v1/chat/completions} endpoint.
 */
@Component
public class LlamaChatModel implements ChatModel {

    private final LlamaClient llama;

    public LlamaChatModel(LlamaClient llama) {
        this.llama = llama;
    }

    @Override
    public String chat(List<Message> messages, Integer maxTokens, Double temperature) throws Exception {
        List<Map<String, Object>> payload = messages.stream().map(Message::toMap).toList();
        return llama.chat(payload, maxTokens, temperature);
    }
}
