package com.example.onlineresearcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client over llama-server's OpenAI-compatible {@code /v1/chat/completions} endpoint. It turns a
 * system prompt plus a user prompt into a chat request and returns the assistant's text reply. Kept
 * identical to roleflow.
 */
@Component
public class LlamaClient {
    @Value("${llama.client-host:127.0.0.1}") private String host = "127.0.0.1";
    @Value("${llama.port:8081}") private int port = 8081;
    @Value("${llama.alias:qwen2.5-3b-instruct}") private String model = "qwen2.5-3b-instruct";
    @Value("${llama.cache-prompt:true}") private boolean cachePrompt = true;
    @Value("${prompt.max-tokens:1024}") private int maxTokens = 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;

    public LlamaClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Sends {@code userPrompt} (optionally preceded by {@code systemPrompt}) to llama-server and returns
     * the assistant reply. A null/blank system prompt is omitted; a null max-tokens/temperature falls back
     * to the server/configured default. This is a stateless convenience over {@link #chat}.
     */
    public String ask(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature)
            throws Exception {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        return chat(messages, maxTokens, temperature);
    }

    /**
     * Sends a prebuilt list of chat messages to llama-server and returns the assistant reply. Used by the
     * conversation memory, which assembles system + prior turns + the new prompt itself.
     */
    public String chat(List<Map<String, Object>> messages, Integer maxTokens, Double temperature)
            throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        int responseTokens = maxTokens != null && maxTokens > 0 ? maxTokens : this.maxTokens;
        Map<String, Object> body = requestBody(model, messages, responseTokens, cachePrompt, temperature);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/v1/chat/completions"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("llama-server " + response.statusCode() + ": " + response.body());
        }
        JsonNode message = mapper.readTree(response.body()).path("choices").path(0).path("message");
        JsonNode content = message.path("content");
        if (content.isMissingNode()) {
            throw new IllegalStateException("Unexpected llama-server response: " + response.body());
        }
        return content.asText();
    }

    static Map<String, Object> requestBody(String model, List<Map<String, Object>> messages,
                                           int responseTokens, boolean cachePrompt, Double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", responseTokens);
        body.put("cache_prompt", cachePrompt);
        body.put("stream", false);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        return body;
    }
}
