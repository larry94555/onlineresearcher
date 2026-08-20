package com.example.onlineresearcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A unit of reusable domain knowledge that the agent can apply when it carries out a task. A skill teaches
 * the model <em>how to think</em> about a subject — its {@link #instructions} are injected into the system
 * prompt. Mirrors roleflow's Skill, but here skills are persisted to disk so the bootstrapped
 * {@code research} skill survives restarts.
 *
 * @param name         the skill's identifier (e.g. "research")
 * @param description  a one-line summary of what the skill teaches, for listings
 * @param instructions the guidance injected into the system prompt
 */
public record Skill(String name, String description, String instructions) {

    /** A {@code {name, description}} summary, for a future {@code skills/list}-style listing. */
    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", name);
        summary.put("description", description);
        return summary;
    }
}
