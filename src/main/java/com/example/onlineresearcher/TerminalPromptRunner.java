package com.example.onlineresearcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads research topics interactively from the terminal (stdin) and prints the agent's reply — either a
 * clarifying question or a researched answer. Runs on a daemon thread so it never blocks application
 * startup, and is disabled automatically during tests via {@code researcher.terminal.enabled=false}. The
 * {@link #loop} method is package-private so it can be driven by a unit test with in-memory streams.
 *
 * <p>The terminal loop <em>is</em> this application's session: when it ends (the user types {@code exit},
 * or stdin closes) the Spring context is closed and the JVM exits. Without that, the embedded web server
 * started by {@code spring-boot-starter-web} keeps non-daemon threads running and the process never ends.
 */
@Component
public class TerminalPromptRunner implements CommandLineRunner {

    private static final String READY_PROMPT = "research> ";
    // Shown as the prompt when the agent paused for a clarifying answer, so the user knows to reply.
    private static final String REPLY_PROMPT = "your answer> ";
    // Shown while the agent works (and then erased), so the user knows it is busy, not waiting on them.
    private static final String THINKING = "researching...";

    private final ResearchService research;
    /** Closed when the prompt loop ends. Null in unit tests, which drive {@link #loop} directly. */
    private final ConfigurableApplicationContext context;

    @Value("${researcher.terminal.enabled:true}") private boolean enabled = true;

    public TerminalPromptRunner(ResearchService research, ConfigurableApplicationContext context) {
        this.research = research;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        Thread thread = new Thread(
                () -> {
                    try {
                        loop(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                                System.out);
                    } finally {
                        shutdown();
                    }
                },
                "researcher-terminal");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Prompt/response loop. Reads one line at a time, runs the research flow, and prints the reply. Stops
     * on end-of-input or when the user types {@code exit} or {@code quit}.
     */
    void loop(BufferedReader in, PrintStream out) {
        out.println("Online Researcher ready. Enter a topic or question to research "
                + "(type 'exit' or 'quit' to stop).");
        try {
            String line;
            out.print(promptFor());
            out.flush();
            while ((line = in.readLine()) != null) {
                String prompt = line.trim();
                if (prompt.equalsIgnoreCase("exit") || prompt.equalsIgnoreCase("quit")) {
                    break;
                }
                if (!prompt.isEmpty()) {
                    out.print(THINKING);   // the agent is working; the user is not expected to type yet
                    out.flush();
                    try {
                        String reply = research.handle(prompt);
                        eraseThinking(out);
                        out.println(reply);
                    } catch (Exception e) {
                        eraseThinking(out);
                        out.println("[error] " + e.getMessage());
                    }
                }
                // The prompt now reflects whether the agent is waiting for the user to reply.
                out.print(promptFor());
                out.flush();
            }
        } catch (Exception e) {
            out.println("[error] terminal input closed: " + e.getMessage());
        }
    }

    /** "your answer>" when the agent paused for a clarifying answer, otherwise the ready prompt. */
    private String promptFor() {
        boolean awaiting;
        try {
            awaiting = research.awaitingReply();
        } catch (Exception e) {
            awaiting = false;
        }
        return awaiting ? REPLY_PROMPT : READY_PROMPT;
    }

    /**
     * Ends the application once the prompt loop is over: closing the context runs the shutdown hooks (the
     * managed llama-server is stopped) and stops the embedded web server that would otherwise keep the JVM
     * alive. {@code System.exit} then guarantees the process ends even if some library thread lingers.
     */
    private void shutdown() {
        if (context == null) return;   // unit test driving loop() directly
        System.exit(SpringApplication.exit(context));
    }

    /** Clears the "researching..." cue from the current line before the reply is printed. */
    private static void eraseThinking(PrintStream out) {
        out.print("\r" + " ".repeat(THINKING.length()) + "\r");
    }
}
