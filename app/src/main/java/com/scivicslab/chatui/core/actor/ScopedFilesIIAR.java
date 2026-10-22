package com.scivicslab.chatui.core.actor;

import java.nio.file.Path;
import java.util.List;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actions of {@link ScopedFiles}: {@code read}, {@code write} and {@code list}. A workflow keeps what an
 * action answers with {@code this.putJson {path: <key>, value: "jexl:result"}}.
 *
 * <pre>{@code
 * - actor: files
 *   method: read
 *   arguments: {path: 'jexl:state.getString("request")'}
 * - actor: this
 *   method: putJson
 *   arguments: {path: cur, value: "jexl:result"}
 * }</pre>
 */
public class ScopedFilesIIAR extends IIActorRef<ScopedFiles> {

    public ScopedFilesIIAR(String actorName, ScopedFiles files, IIActorSystem system) {
        super(actorName, files, system);
    }

    /** Reads a file. Args: {@code {path}}; the message is the file's text. */
    @Action("read")
    public ActionResult read(String args) {
        String path = new JSONObject(args).optString("path", "");
        if (path.isBlank()) return new ActionResult(false, "path is required");
        try {
            return new ActionResult(true, ask(f -> unchecked(() -> f.read(Path.of(path.strip())))).get());
        } catch (Exception e) {
            return new ActionResult(false, "could not read " + path + ": " + reason(e));
        }
    }

    /** Writes a file, creating the directories above it. Args: {@code {path, text}}. */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = new JSONObject(args);
        String path = a.optString("path", "");
        if (path.isBlank()) return new ActionResult(false, "path is required");
        if (!a.has("text") || a.isNull("text")) return new ActionResult(false, "text is required");
        String text = a.getString("text");
        try {
            ask(f -> unchecked(() -> {
                f.write(Path.of(path.strip()), text);
                return null;
            })).get();
            return new ActionResult(true, "wrote " + text.length() + " chars to " + path.strip());
        } catch (Exception e) {
            return new ActionResult(false, "could not write " + path + ": " + reason(e));
        }
    }

    /**
     * Lists the files of a directory. Args: {@code {dir, suffix, depth}} (suffix empty for every file; depth 1 for the
     * directory alone, the default). The message is their paths, one per line; it fails when none matched.
     */
    @Action("list")
    public ActionResult list(String args) {
        JSONObject a = new JSONObject(args);
        String dir = a.optString("dir", "");
        if (dir.isBlank()) return new ActionResult(false, "dir is required");
        String suffix = a.optString("suffix", "").strip();
        int depth;
        try {
            String d = a.optString("depth", "1").strip();
            depth = Math.max(1, Integer.parseInt(d.isEmpty() ? "1" : d));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "depth must be a whole number, not '" + a.opt("depth") + "'");
        }
        try {
            List<Path> found = ask(f -> unchecked(() -> f.list(Path.of(dir.strip()), suffix, depth))).get();
            if (found.isEmpty()) return new ActionResult(false, "no file ending in '" + suffix + "' under " + dir);
            return new ActionResult(true, String.join("\n", found.stream().map(Path::toString).toList()));
        } catch (Exception e) {
            return new ActionResult(false, "could not list " + dir + ": " + reason(e));
        }
    }

    private interface Io<R> {
        R get() throws java.io.IOException;
    }

    private static <R> R unchecked(Io<R> io) {
        try {
            return io.get();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String reason(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage();
    }
}
