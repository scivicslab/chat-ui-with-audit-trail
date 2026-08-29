package com.scivicslab.chatui.core.actor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Holds the catalog of skills found in this system's skill roots, and serves one skill's
 * instructions on demand ({@code SkillAndAgentsFile_260830_oo01}).
 *
 * <p>A skill is a directory containing a {@code SKILL.md} whose YAML frontmatter carries a
 * {@code name} and a {@code description}, per the Agent Skills specification. Conforming to that
 * specification is what lets this system read the very same directory Claude Code reads
 * ({@code ~/.claude/skills}) instead of keeping a second copy of the same instructions.</p>
 *
 * <p>This class implements the first two of the specification's three loading levels: the catalog
 * ({@code name} + {@code description} of every skill, injected into every conversation's system
 * prompt) and the body (one skill's full {@code SKILL.md}, fetched only when a conversation calls
 * {@code load_skill}). The third level — a skill's {@code references/}, {@code scripts/} and
 * {@code assets/} files — needs no mechanism here: the conversation reads them with the ordinary
 * {@code read} tool, using the directory path this class reports alongside the body.</p>
 *
 * <p>One instance for the whole actor system, reached like any other POJO-actor. Skills are
 * indexed at construction and re-indexed by {@link #scan()}; each body is read from disk at the
 * moment it is requested, so editing a {@code SKILL.md} takes effect without a rescan.</p>
 */
public class SkillRegistry {

    /** Longest {@code name} the Agent Skills specification allows. */
    private static final int NAME_MAX = 64;
    /** Longest {@code description} the specification allows. */
    private static final int DESCRIPTION_MAX = 1024;
    /** Lowercase alphanumerics and single hyphens, not leading or trailing. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /**
     * One indexed skill.
     *
     * @param name        the skill's {@code name}, equal to its directory's name
     * @param description the skill's {@code description} — what it does and when to use it
     * @param skillFile   absolute path of the skill's {@code SKILL.md}
     */
    public record Skill(String name, String description, Path skillFile) {
        /** @return the skill's directory, which is where {@code references/} etc. live */
        public Path directory() {
            return skillFile.getParent();
        }
    }

    private final List<Path> roots;
    private final Map<String, Skill> catalog = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();

    /**
     * @param roots directories whose immediate subdirectories are candidate skills, in precedence
     *              order — when two roots offer the same skill name, the earlier root wins
     */
    public SkillRegistry(List<Path> roots) {
        this.roots = List.copyOf(roots);
        scan();
    }

    /**
     * Re-indexes every root, replacing the catalog.
     *
     * <p>A directory without a {@code SKILL.md} is not a skill and is passed over in silence. A
     * directory that has one but whose frontmatter does not conform is recorded in
     * {@link #getProblems()} rather than skipped quietly, because a skill that was installed but
     * does not take effect is otherwise indistinguishable from one that was never installed.</p>
     *
     * @return the number of skills now in the catalog
     */
    public int scan() {
        catalog.clear();
        problems.clear();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                problems.add(root + ": not a directory");
                continue;
            }
            try (Stream<Path> entries = Files.list(root)) {
                for (Path dir : entries.sorted().toList()) {
                    if (!Files.isDirectory(dir)) continue;
                    Path skillFile = dir.resolve("SKILL.md");
                    if (!Files.isRegularFile(skillFile)) continue;
                    index(dir, skillFile);
                }
            } catch (IOException e) {
                problems.add(root + ": " + e.getMessage());
            }
        }
        return catalog.size();
    }

    private void index(Path dir, Path skillFile) {
        String text;
        try {
            text = Files.readString(skillFile);
        } catch (IOException e) {
            problems.add(skillFile + ": " + e.getMessage());
            return;
        }
        Map<String, String> front = parseFrontmatter(text);
        if (front == null) {
            problems.add(skillFile + ": no YAML frontmatter");
            return;
        }
        String name = front.get("name");
        String description = front.get("description");
        if (name == null || name.isBlank()) {
            problems.add(skillFile + ": frontmatter has no 'name'");
            return;
        }
        if (description == null || description.isBlank()) {
            problems.add(skillFile + ": frontmatter has no 'description'");
            return;
        }
        if (name.length() > NAME_MAX || !NAME_PATTERN.matcher(name).matches()) {
            problems.add(skillFile + ": invalid name '" + name
                    + "' (lowercase letters, digits and single hyphens, at most " + NAME_MAX + " characters)");
            return;
        }
        if (!name.equals(dir.getFileName().toString())) {
            problems.add(skillFile + ": name '" + name + "' does not match its directory '"
                    + dir.getFileName() + "'");
            return;
        }
        if (description.length() > DESCRIPTION_MAX) {
            problems.add(skillFile + ": description is " + description.length()
                    + " characters, over the " + DESCRIPTION_MAX + " limit");
            return;
        }
        Skill existing = catalog.get(name);
        if (existing != null) {
            problems.add(skillFile + ": name '" + name + "' is already provided by "
                    + existing.skillFile() + " — this one is ignored");
            return;
        }
        catalog.put(name, new Skill(name, description, skillFile));
    }

    /**
     * The catalog as it is injected into a conversation's system prompt — the specification's
     * first loading level, and the only part that is present whether or not it is used.
     *
     * @return one line per skill, or {@code ""} when no skill is indexed
     */
    public String catalogText() {
        if (catalog.isEmpty()) return "";
        StringBuilder buf = new StringBuilder(
                "Available skills — instructions you can load when the task calls for one. "
                + "Call load_skill(name) to read one in full before you act on it:\n");
        for (Skill skill : catalog.values()) {
            buf.append("- ").append(skill.name()).append(": ").append(skill.description()).append('\n');
        }
        return buf.toString();
    }

    /**
     * One skill's instructions — the specification's second loading level, read from disk on each
     * call. The directory is stated on the first line so the conversation can go on to read that
     * skill's own {@code references/} and {@code scripts/} files with the {@code read} tool.
     *
     * @param name the skill's name, as listed in {@link #catalogText()}
     * @return the skill's Markdown body preceded by its directory, or {@code null} if no skill of
     *         that name is indexed
     */
    public String bodyOf(String name) {
        Skill skill = catalog.get(name);
        if (skill == null) return null;
        String text;
        try {
            text = Files.readString(skill.skillFile());
        } catch (IOException e) {
            return "error: cannot read " + skill.skillFile() + ": " + e.getMessage();
        }
        return "Skill \"" + skill.name() + "\" — directory: " + skill.directory()
                + "\n(read that directory's files with the read tool when this text refers to them)\n\n"
                + bodyAfterFrontmatter(text);
    }

    /** @return every indexed skill, in catalog order */
    public List<Skill> getSkills() {
        return new ArrayList<>(catalog.values());
    }

    /** @return the names of the indexed skills, in catalog order */
    public List<String> getSkillNames() {
        return new ArrayList<>(catalog.keySet());
    }

    /** @return one message per skill directory that was found but could not be indexed */
    public List<String> getProblems() {
        return new ArrayList<>(problems);
    }

    /** @return the roots this registry indexes, in precedence order */
    public List<Path> getRoots() {
        return roots;
    }

    /**
     * Reads the top-level scalar entries of a Markdown file's YAML frontmatter.
     *
     * <p>Deliberately not a YAML parser: the specification's required fields are two top-level
     * scalars, so this recognises {@code key: value}, quoted values, and {@code |}/{@code >} block
     * scalars, and steps over everything nested (such as {@code metadata:}) without interpreting
     * it.</p>
     *
     * @param text the whole file
     * @return the top-level entries, or {@code null} if the file has no frontmatter
     */
    static Map<String, String> parseFrontmatter(String text) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) return null;
        Map<String, String> entries = new LinkedHashMap<>();
        int i = 1;
        while (i < lines.size() && !lines.get(i).strip().equals("---")) {
            String line = lines.get(i);
            i++;
            if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.equals("|") || value.equals(">") || value.equals("|-") || value.equals(">-")) {
                boolean fold = value.startsWith(">");
                StringBuilder block = new StringBuilder();
                while (i < lines.size() && !lines.get(i).strip().equals("---")
                        && (lines.get(i).isBlank() || lines.get(i).startsWith(" ") || lines.get(i).startsWith("\t"))) {
                    if (block.length() > 0) block.append(fold ? ' ' : '\n');
                    block.append(lines.get(i).strip());
                    i++;
                }
                entries.put(key, block.toString());
            } else {
                entries.put(key, unquote(value));
            }
        }
        return entries;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** @return everything after the frontmatter's closing {@code ---}, or the whole text if none */
    static String bodyAfterFrontmatter(String text) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) return text;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return String.join("\n", lines.subList(i + 1, lines.size())).strip();
            }
        }
        return text;
    }
}
