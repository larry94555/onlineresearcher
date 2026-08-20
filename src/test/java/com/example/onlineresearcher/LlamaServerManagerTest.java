package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the bind guard: llama-server's inference API and Web UI are unauthenticated unless an API key is
 * passed, so anything wider than loopback must carry one.
 */
class LlamaServerManagerTest {

    @Test
    void loopbackAddressesAreRecognized() {
        assertTrue(LlamaServerManager.isLoopback("127.0.0.1"));
        assertTrue(LlamaServerManager.isLoopback("127.1.2.3"));
        assertTrue(LlamaServerManager.isLoopback("localhost"));
        assertTrue(LlamaServerManager.isLoopback("::1"));
        assertTrue(LlamaServerManager.isLoopback("[::1]"));
        assertTrue(LlamaServerManager.isLoopback(""), "blank leaves llama-server on its own default");
        assertFalse(LlamaServerManager.isLoopback("0.0.0.0"));
        assertFalse(LlamaServerManager.isLoopback("192.168.1.10"));
        assertFalse(LlamaServerManager.isLoopback("::"));
    }

    @Test
    void loopbackBindNeedsNoApiKey() {
        LlamaServerManager.requireAuthenticatedBind("127.0.0.1", "");
    }

    @Test
    void nonLoopbackBindWithoutApiKeyIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> LlamaServerManager.requireAuthenticatedBind("0.0.0.0", ""));
        assertTrue(error.getMessage().contains("llama.api-key"), error.getMessage());
    }

    @Test
    void nonLoopbackBindIsAllowedWithAnApiKey() {
        LlamaServerManager.requireAuthenticatedBind("0.0.0.0", "s3cret");
    }

    @Test
    void defaultsBindToLoopbackAndPassNoApiKey() {
        List<String> command = new LlamaServerManager().buildCommand();
        int host = command.indexOf("--host");
        assertTrue(host >= 0, command.toString());
        assertEquals("127.0.0.1", command.get(host + 1));
        assertFalse(command.contains("--api-key"), command.toString());
    }
}
