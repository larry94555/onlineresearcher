package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the Spring context wires every bean together. The test profile
 * ({@code src/test/resources/application.properties}) disables the llama-server launch, the terminal
 * reader, and the network-backed providers, so this loads without a model or network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OnlineResearcherApplicationTests {

    @Test
    void contextLoads() {
        // Bean wiring is exercised by the context starting up.
    }
}
