package com.example.onlineresearcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Disk-backed skill registry. Skills are stored as markdown files under {@code skills.dir} (one file per
 * skill, named {@code <name>.md}) with a tiny header block:
 *
 * <pre>
 * name: research
 * description: How to research a topic on the web and fact-check findings
 * ---
 * &lt;instructions...&gt;
 * </pre>
 *
 * Persisting skills lets the agent <em>create</em> a skill once (by researching best practices on the web)
 * and reuse it on every later run, exactly as the task requires.
 */
@Component
public class SkillStore {
    private static final Logger log = LoggerFactory.getLogger(SkillStore.class);
    private static final String SEPARATOR = "---";

    private final Path directory;

    public SkillStore(@Value("${skills.dir:skills}") String directory) {
        this.directory = Path.of(directory == null || directory.isBlank() ? "skills" : directory);
    }

    /** The directory skills are read from / written to. */
    public Path directory() {
        return directory;
    }

    /** Whether a skill with this name exists on disk. */
    public synchronized boolean contains(String name) {
        return name != null && Files.exists(fileFor(name));
    }

    /** Loads the named skill, or returns {@code null} if it is not present / unreadable. */
    public synchronized Skill get(String name) {
        if (name == null) return null;
        Path file = fileFor(name);
        if (!Files.exists(file)) return null;
        try {
            return parse(name, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[skills] failed to read skill '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /** Writes (or overwrites) a skill to disk. */
    public synchronized void save(Skill skill) {
        if (skill == null || skill.name() == null || skill.name().isBlank()) {
            throw new IllegalArgumentException("skill name is required");
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(fileFor(skill.name()), render(skill), StandardCharsets.UTF_8);
            log.info("[skills] saved skill '{}' to {}", skill.name(), fileFor(skill.name()));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save skill " + skill.name(), e);
        }
    }

    /**
     * The persisted version of a skill (from a sidecar {@code <name>.version} file), or 0 when there is no
     * marker — i.e. a skill written by an older build. Lets an owner rebuild a stale skill automatically.
     */
    public synchronized int version(String name) {
        try {
            Path file = versionFile(name);
            if (!Files.exists(file)) return 0;
            return Integer.parseInt(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** Records the version of a skill so {@link #version(String)} can detect a later upgrade. */
    public synchronized void setVersion(String name, int version) {
        try {
            Files.createDirectories(directory);
            Files.writeString(versionFile(name), Integer.toString(version), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[skills] could not write version marker for skill '{}': {}", name, e.getMessage());
        }
    }

    private Path versionFile(String name) {
        return directory.resolve(name.trim().toLowerCase(Locale.ROOT) + ".version");
    }

    Path fileFor(String name) {
        return directory.resolve(name.trim().toLowerCase(Locale.ROOT) + ".md");
    }

    static String render(Skill skill) {
        return "name: " + skill.name() + "\n"
                + "description: " + oneLine(skill.description()) + "\n"
                + SEPARATOR + "\n"
                + (skill.instructions() == null ? "" : skill.instructions().strip()) + "\n";
    }

    static Skill parse(String fallbackName, String content) {
        if (content == null) content = "";
        String name = fallbackName;
        String description = "";
        String[] lines = content.split("\n", -1);
        int separatorIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().equals(SEPARATOR)) {
                separatorIndex = i;
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (key.equals("name") && !value.isBlank()) name = value;
                else if (key.equals("description")) description = value;
            }
        }
        String instructions = separatorIndex >= 0
                ? String.join("\n", java.util.Arrays.copyOfRange(lines, separatorIndex + 1, lines.length)).strip()
                : content.strip();
        return new Skill(name, description, instructions);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
