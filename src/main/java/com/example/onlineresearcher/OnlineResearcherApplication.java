package com.example.onlineresearcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Online Researcher console application. Boot starts the supporting beans (the
 * llama-server manager, conversation memory, skills, and web-research providers) and the
 * {@link TerminalPromptRunner} reads research topics from the terminal.
 */
@SpringBootApplication
public class OnlineResearcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnlineResearcherApplication.class, args);
    }
}
