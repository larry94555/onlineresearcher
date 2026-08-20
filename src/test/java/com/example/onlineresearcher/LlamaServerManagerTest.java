package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    void defaultsBindToLoopback() {
        List<String> command = new LlamaServerManager().buildCommand();
        int host = command.indexOf("--host");
        assertTrue(host >= 0, command.toString());
        assertEquals("127.0.0.1", command.get(host + 1));
    }

    @Test
    void theApiKeyTravelsInTheEnvironmentNotTheCommandLine() {
        // Process arguments are readable by anyone who can list processes, and the command is logged.
        assertFalse(new LlamaServerManager().buildCommand().contains("--api-key"));

        Map<String, String> environment = new HashMap<>();
        LlamaServerManager.applyApiKey(environment, "  s3cret  ");
        assertEquals("s3cret", environment.get("LLAMA_API_KEY"));

        Map<String, String> unauthenticated = new HashMap<>();
        LlamaServerManager.applyApiKey(unauthenticated, "");
        assertTrue(unauthenticated.isEmpty(), "no key configured means no environment entry");
    }

    @Test
    void aClientHostThatCannotReachTheBindAddressIsRefused() {
        // Otherwise the only symptom is a health check that never succeeds, which reads as a slow start.
        IllegalStateException wrongFamily = assertThrows(IllegalStateException.class,
                () -> LlamaServerManager.requireReachableClientHost("::1", "127.0.0.1"));
        assertTrue(wrongFamily.getMessage().contains("llama.client-host=::1"), wrongFamily.getMessage());

        // Same family, different address: bound to one interface, dialled on another.
        assertThrows(IllegalStateException.class,
                () -> LlamaServerManager.requireReachableClientHost("192.168.1.5", "127.0.0.1"));
    }

    @Test
    void reachableAndUnknowableClientHostsAreLeftAlone() {
        LlamaServerManager.requireReachableClientHost("::1", "::1");
        LlamaServerManager.requireReachableClientHost("::1", "[::1]");
        LlamaServerManager.requireReachableClientHost("127.0.0.1", "127.0.0.1");
        LlamaServerManager.requireReachableClientHost("0.0.0.0", "127.0.0.1");   // wildcard: every interface
        LlamaServerManager.requireReachableClientHost("::", "127.0.0.1");
        // A hostname can resolve to either family, or to several addresses — do not guess.
        LlamaServerManager.requireReachableClientHost("localhost", "127.0.0.1");
        LlamaServerManager.requireReachableClientHost("::1", "localhost");
    }

    @Test
    void anUnreachableConfigurationStartsNothingAndWaitsForNothing() {
        LlamaServerManager manager = new LlamaServerManager();
        ReflectionTestUtils.setField(manager, "manageServer", true);
        ReflectionTestUtils.setField(manager, "host", "::1");
        ReflectionTestUtils.setField(manager, "clientHost", "127.0.0.1");
        ReflectionTestUtils.setField(manager, "binary", "no-such-binary-must-never-run");

        Instant began = Instant.now();
        assertThrows(IllegalStateException.class, manager::start);
        Duration took = Duration.between(began, Instant.now());

        // The command is built immediately before launch(), so a null one proves no process was started
        // and no readiness poll was entered — the failure mode was a ten-minute wait, not a slow one.
        assertEquals(null, ReflectionTestUtils.getField(manager, "command"), "nothing was launched");
        assertEquals(null, ReflectionTestUtils.getField(manager, "proc"), "no process handle was kept");
        assertTrue(took.toSeconds() < 30, "must fail fast, took " + took);
    }

    @Test
    void aKeyPassedThroughExtraArgsIsRedactedFromTheLog() {
        List<String> logged = LlamaServerManager.redactSecrets(
                List.of("llama-server", "--api-key", "s3cret", "--jinja"));

        assertEquals(List.of("llama-server", "--api-key", "***", "--jinja"), logged);
    }
}
