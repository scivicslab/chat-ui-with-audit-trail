package com.scivicslab.chatui.core.actor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Top-level grouping actor for one project — a {@code createChild} parent, exactly like
 * {@link CallWatchdog}/{@link CollaborationGraph}, that additionally carries the project's
 * working directory and the instructions found there.
 *
 * <p>Everything one project's execution needs — its conversation tabs ({@code chat-...}) — lives
 * as a descendant of one {@code Project} instance. {@code outputMultiplexer}, {@code callWatchdog}
 * and {@code collaborationGraph} deliberately stay outside this tree — see
 * {@code ProjectScopedActorTree_260829_oo01}.</p>
 *
 * <p>The working directory is what gives a conversation a position in the file tree, which
 * {@code AGENTS.md} needs and this system otherwise lacks: an agent editing a file resolves
 * "the nearest {@code AGENTS.md}" from the file it is editing, and a conversation has no such
 * file. The project's directory stands in for it, so every conversation in one project receives
 * that directory's instructions ({@code SkillAndAgentsFile_260830_oo01}).</p>
 */
public class Project {

    /** The standard file name, per the AGENTS.md open format. */
    public static final String AGENTS_FILE = "AGENTS.md";
    /** Read only when there is no {@code AGENTS.md}; every repository here still carries this one. */
    public static final String CLAUDE_FILE = "CLAUDE.md";

    private Path workingDir;
    private Path instructionsFile;
    private String instructions;

    /**
     * Points this project at a directory and reads that directory's instructions, preferring
     * {@code AGENTS.md} and falling back to {@code CLAUDE.md}. A project with no working directory,
     * or a directory with neither file, simply has no instructions — that is the normal state, not
     * an error.
     *
     * @param dir the project's working directory, or {@code null} to clear it
     * @return a one-line account of what was loaded, or an {@code error: ...} string
     */
    public String setWorkingDir(Path dir) {
        workingDir = dir;
        instructionsFile = null;
        instructions = null;
        if (dir == null) return "ok: working directory cleared";
        if (!Files.isDirectory(dir)) return "error: not a directory: " + dir;
        Path candidate = dir.resolve(AGENTS_FILE);
        if (!Files.isRegularFile(candidate)) {
            candidate = dir.resolve(CLAUDE_FILE);
            if (!Files.isRegularFile(candidate)) {
                return "ok: working directory set to " + dir + " (no " + AGENTS_FILE
                        + " and no " + CLAUDE_FILE + " there)";
            }
        }
        try {
            instructions = Files.readString(candidate).strip();
            instructionsFile = candidate;
        } catch (IOException e) {
            return "error: cannot read " + candidate + ": " + e.getMessage();
        }
        return "ok: working directory set to " + dir + ", instructions read from "
                + candidate.getFileName() + " (" + instructions.length() + " characters)";
    }

    /** Re-reads the instructions from the current working directory. @return the same account as
     *  {@link #setWorkingDir(Path)} */
    public String reloadInstructions() {
        return setWorkingDir(workingDir);
    }

    /** @return this project's working directory, or {@code null} if it has none */
    public Path getWorkingDir() {
        return workingDir;
    }

    /** @return the file the instructions were read from, or {@code null} if there are none */
    public Path getInstructionsFile() {
        return instructionsFile;
    }

    /** @return this project's instructions, or {@code null} if it has none */
    public String getInstructions() {
        return instructions;
    }
}
