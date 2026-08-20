package com.example.onlineresearcher;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launches and supervises a local {@code llama-server} process. The command and lifecycle are kept
 * identical to roleflow: the same flags, the same health-check polling, and the same watchdog. The field
 * initializers below mirror the {@code @Value} defaults so the command builder can be exercised in plain
 * unit tests (without a Spring context wiring the properties).
 */
@Component
public class LlamaServerManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlamaServerManager.class);

    @Value("${llama.manage-server:true}") private boolean manageServer = true;
    @Value("${llama.binary:}") private String binary = "";
    @Value("${llama.profile:small}") private String profile = "small";
    @Value("${llama.hf-model:}") private String hfModel = "";
    @Value("${llama.model-path:}") private String modelPath = "";
    @Value("${llama.alias:qwen2.5-3b-instruct}") private String alias = "qwen2.5-3b-instruct";
    @Value("${llama.host:127.0.0.1}") private String host = "127.0.0.1";
    @Value("${llama.port:8081}") private int port = 8081;
    @Value("${llama.client-host:127.0.0.1}") private String clientHost = "127.0.0.1";
    @Value("${llama.ctx-size:0}") private int ctxSize = 0;
    @Value("${llama.gpu-layers:-1}") private int gpuLayers = -1;
    @Value("${llama.threads:0}") private int threads = 0;
    @Value("${llama.parallel:1}") private int parallel = 1;
    @Value("${llama.extra-args:}") private String extraArgs = "";
    @Value("${llama.auto-restart:true}") private boolean autoRestart = true;
    @Value("${llama.health-interval-seconds:15}") private int healthInterval = 15;
    @Value("${llama.cache-reuse:256}") private int cacheReuse = 256;
    @Value("${llama.draft-hf-model:}") private String draftHf = "";
    @Value("${llama.draft-model-path:}") private String draftPath = "";
    @Value("${llama.draft-tokens:16}") private int draftTokens = 16;
    @Value("${llama.draft-gpu-layers:-1}") private int draftGpuLayers = -1;
    // Passed to llama-server as --api-key. Required before the server may bind to a non-loopback address,
    // because llama-server's inference API and Web UI are otherwise unauthenticated.
    @Value("${llama.api-key:}") private String apiKey = "";

    private final HttpClient http = HttpClient.newHttpClient();
    private volatile Process proc;
    private volatile boolean shuttingDown;
    private List<String> command;
    private Thread watchdog;

    @PostConstruct
    public void start() {
        if (!manageServer) {
            log.info("[llama] manage-server=false; expecting an external llama-server on port {}", port);
            return;
        }
        requireAuthenticatedBind(host, apiKey);
        command = buildCommand();
        log.info("[llama] launching: {}", String.join(" ", command));
        if (!launch()) {
            // A launch failure (missing binary, bad path) never becomes healthy: polling for ten minutes
            // would only hang startup, so report it and leave the server unmanaged.
            log.error("[llama] server not started; requests to it will fail until the binary is available.");
            return;
        }
        waitUntilReady();
        if (healthy()) {
            startWatchdog();
        } else {
            log.warn("[llama] not healthy yet; watchdog NOT started (avoid restart-storm during model download).");
        }
    }

    /**
     * llama-server serves its inference API and Web UI without authentication unless an API key is set, so
     * a non-loopback bind must carry one. Refuses to start rather than exposing the model to the network.
     */
    static void requireAuthenticatedBind(String host, String apiKey) {
        if (isLoopback(host) || (apiKey != null && !apiKey.isBlank())) return;
        throw new IllegalStateException("llama.host=" + host + " exposes llama-server beyond this machine "
                + "but llama.api-key is not set. Either bind to 127.0.0.1 (the default) or set llama.api-key.");
    }

    /** True when the bind address reaches only this machine. Blank means llama-server's own default (loopback). */
    static boolean isLoopback(String value) {
        String h = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return true;
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1") || h.startsWith("127.");
    }

    String resolveBinary() {
        if (binary != null && !binary.isBlank()) return binary.trim();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? "llama-server.exe" : "llama-server";
    }

    List<String> buildCommand() {
        int ctx = ctxSize > 0 ? ctxSize : 8192;
        int ngl = gpuLayers >= 0 ? gpuLayers : 0;
        int workerThreads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveBinary());
        if (!modelPath.isBlank()) {
            cmd.add("-m");
            cmd.add(modelPath);
        } else {
            cmd.add("-hf");
            cmd.add(hfModel.isBlank() ? profileModel(profile) : hfModel);
        }
        cmd.add("--host"); cmd.add(host);
        cmd.add("--port"); cmd.add(String.valueOf(port));
        cmd.add("-c"); cmd.add(String.valueOf(ctx));
        cmd.add("-t"); cmd.add(String.valueOf(workerThreads));
        cmd.add("-ngl"); cmd.add(String.valueOf(ngl));
        cmd.add("--parallel"); cmd.add(String.valueOf(Math.max(1, parallel)));
        cmd.add("--alias"); cmd.add(alias);
        cmd.add("--jinja");
        if (!apiKey.isBlank()) {
            cmd.add("--api-key");
            cmd.add(apiKey.trim());
        }
        if (cacheReuse > 0) {
            cmd.add("--cache-reuse");
            cmd.add(String.valueOf(cacheReuse));
        }
        if (!draftPath.isBlank() || !draftHf.isBlank()) {
            if (!draftPath.isBlank()) { cmd.add("-md"); cmd.add(draftPath); }
            else { cmd.add("-hfd"); cmd.add(draftHf); }
            cmd.add("--draft-max"); cmd.add(String.valueOf(Math.max(1, draftTokens)));
            if (draftGpuLayers >= 0) { cmd.add("-ngld"); cmd.add(String.valueOf(draftGpuLayers)); }
        }
        if (extraArgs != null && !extraArgs.isBlank()) {
            for (String arg : extraArgs.trim().split("\\s+")) cmd.add(arg);
        }
        return cmd;
    }

    private String profileModel(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "medium" -> "Qwen/Qwen2.5-7B-Instruct-GGUF:Q4_K_M";
            case "large" -> "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF:Q4_K_M";
            default -> "Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M";
        };
    }

    /** Starts the process. Returns false when it could not be started (e.g. the binary is missing). */
    private boolean launch() {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectOutput(new File("llama-server.log"));
            builder.redirectError(new File("llama-server.log"));
            proc = builder.start();
            log.info("[llama] started on port {} (profile={}, parallel={}, logs -> llama-server.log)",
                    port, profile, Math.max(1, parallel));
            return true;
        } catch (IOException e) {
            log.error("[llama] failed to start '{}': {}", command.isEmpty() ? "" : command.get(0), e.getMessage());
            return false;
        }
    }

    /**
     * Ends the process we are holding, if any, and waits for it to actually exit. A replacement cannot bind
     * the port while the old server still holds it, and overwriting {@code proc} would lose the only handle
     * we have for stopping it.
     */
    private void terminateExisting() {
        Process existing = proc;
        proc = null;
        if (existing == null || !existing.isAlive()) return;
        existing.destroy();
        try {
            if (!existing.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                existing.destroyForcibly();
                existing.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[llama] previous server process ended before relaunch.");
    }

    private boolean healthy() {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                    URI.create("http://" + clientHost + ":" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitUntilReady() {
        for (int i = 0; i < 600; i++) {
            if (healthy()) {
                log.info("[llama] ready.");
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("[llama] not ready after 600s; check llama-server.log");
    }

    private void startWatchdog() {
        if (!autoRestart) return;
        watchdog = new Thread(() -> {
            while (!shuttingDown) {
                try {
                    Thread.sleep(Math.max(5, healthInterval) * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (shuttingDown) return;
                if (proc == null || !proc.isAlive() || !healthy()) {
                    log.warn("[llama] watchdog: server unhealthy; restarting...");
                    terminateExisting();
                    if (shuttingDown) return;
                    if (launch()) {
                        waitUntilReady();
                    } else {
                        log.error("[llama] watchdog: relaunch failed; will retry on the next check.");
                    }
                }
            }
        }, "llama-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        log.info("[llama] watchdog on (checks every {}s).", Math.max(5, healthInterval));
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        if (watchdog != null) watchdog.interrupt();
        if (proc != null && proc.isAlive()) {
            proc.destroy();
            log.info("[llama] stopped.");
        }
    }
}
