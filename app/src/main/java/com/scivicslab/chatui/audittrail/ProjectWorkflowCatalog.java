package com.scivicslab.chatui.audittrail;

import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.MatrixCode;

import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Turing workflows one project can run ({@code ProjectPerspective_260911_oo01}).
 *
 * <p>They come from two places. The project's own are the {@code *.yaml} files under
 * {@code workflows/} in its working directory; those are the project's to edit and run. The
 * bundled ones are the {@code /workflows/*.yaml} files shipped on this program's classpath; those
 * are read-only examples and defaults. A project file with the same name as a bundled one hides
 * it: when a project has its own {@code prompt-construction-default}, that is the one meant.</p>
 *
 * <p>A plain object over the file system, with no state beyond the working directory it was given.
 * Built afresh per request by {@link ProjectWorkflowResource}; the project's working directory is
 * read from the {@code Project} actor each time, so a project pointed elsewhere is read from
 * there on the next call.</p>
 */
public final class ProjectWorkflowCatalog {

    private static final Logger LOG = Logger.getLogger(ProjectWorkflowCatalog.class.getName());

    /** Where a workflow came from. */
    public static final String ORIGIN_PROJECT = "project";
    /** Where a workflow came from. */
    public static final String ORIGIN_BUNDLED = "bundled";

    /** The directory under the project's working directory that holds its workflows. */
    public static final String SUBDIR = "workflows";

    /** The classpath directory the bundled workflows live in. */
    static final String BUNDLED_DIR = "/workflows";

    /** A workflow's basename may hold only these: no separators, so it cannot leave its directory. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Pattern NAME_LINE = Pattern.compile("(?m)^name:\\s*(.+?)\\s*$");
    private static final Pattern DESCRIPTION_LINE = Pattern.compile("(?m)^description:\\s*(.*?)\\s*$");

    /** How a workflow written for Turing-workflow 3 wrote it. Still found in older files. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    /** The one variable a run fills in itself: the previous transition's result. */
    private static final String RESULT_VARIABLE = "result";

    /**
     * One row of the catalog.
     *
     * @param name        the basename without {@code .yaml}; what {@link #read} takes
     * @param title       the workflow's own {@code name:} field, or {@code name} when it has none
     * @param description the first line of its {@code description:} field, or {@code ""}
     * @param origin      {@link #ORIGIN_PROJECT} or {@link #ORIGIN_BUNDLED}
     * @param editable    whether it may be written back — true only for the project's own
     */
    public record Entry(String name, String title, String description, String origin,
                        boolean editable) {}

    /**
     * One workflow's text.
     *
     * @param name     the basename without {@code .yaml}
     * @param yaml     the file's whole text
     * @param origin   {@link #ORIGIN_PROJECT} or {@link #ORIGIN_BUNDLED}
     * @param editable whether it may be written back
     * @param params   the inputs it declares, in declared order — see {@link #paramsOf}
     */
    public record Document(String name, String yaml, String origin, boolean editable,
                           List<ParamSpec> params) {}

    /**
     * One declared input of a workflow ({@code WorkflowInputParams_260701_oo01}).
     *
     * @param key          the name the workflow reads it by: {@code state.getString('key')}
     * @param label        what to call it in a form, or {@code null} to use the key
     * @param description  what it is for, or {@code ""}
     * @param type         which form field to draw: {@code text}, {@code textarea}, {@code int},
     *                     {@code bool}, {@code select} or {@code path}; {@code text} when absent
     *                     or unknown to the form
     * @param required     whether a run must be given a value: as declared, else true when there
     *                     is no default
     * @param defaultValue the value to start the field with, or {@code null}
     * @param options      the choices when {@code type} is {@code select}, else empty
     */
    public record ParamSpec(String key, String label, String description, String type,
                            boolean required, String defaultValue, List<String> options) {}

    private final Path projectDir;

    /**
     * @param workingDir the project's working directory, or {@code null} when it has none — then
     *                   only the bundled workflows are listed
     */
    public ProjectWorkflowCatalog(Path workingDir) {
        this.projectDir = workingDir == null ? null : workingDir.resolve(SUBDIR);
    }

    /**
     * @return whether {@code name} may name a workflow: a basename with no separators
     */
    public static boolean isSafeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    /**
     * Lists the workflows, the project's own first, then the bundled ones not hidden by them.
     *
     * @param query text to match, case-insensitively, against a workflow's basename, title,
     *              description and body; {@code null} or blank lists all
     * @return the matching rows, in the order described
     */
    public List<Entry> list(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : projectFiles().entrySet()) {
            Entry entry = entryOf(e.getKey(), e.getValue(), ORIGIN_PROJECT);
            if (matches(entry, e.getValue(), q)) byName.put(e.getKey(), entry);
        }
        for (Map.Entry<String, String> e : bundledFiles().entrySet()) {
            if (byName.containsKey(e.getKey()) || projectFiles().containsKey(e.getKey())) continue;
            Entry entry = entryOf(e.getKey(), e.getValue(), ORIGIN_BUNDLED);
            if (matches(entry, e.getValue(), q)) byName.put(e.getKey(), entry);
        }
        return List.copyOf(byName.values());
    }

    /**
     * Reads one workflow, the project's own if it has one of that name, else the bundled one.
     *
     * @param name the basename without {@code .yaml}
     * @return the workflow, or {@code null} when there is none of that name or the name is unsafe
     */
    public Document read(String name) {
        if (!isSafeName(name)) return null;
        if (projectDir != null) {
            Path file = projectDir.resolve(name + ".yaml");
            if (Files.isRegularFile(file)) {
                try {
                    return documentOf(name, Files.readString(file, StandardCharsets.UTF_8),
                            ORIGIN_PROJECT, true);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not read " + file, e);
                    return null;
                }
            }
        }
        try (InputStream in = getClass().getResourceAsStream(BUNDLED_DIR + "/" + name + ".yaml")) {
            if (in == null) return null;
            return documentOf(name, new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    ORIGIN_BUNDLED, false);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read bundled workflow " + name, e);
            return null;
        }
    }

    /**
     * Checks that {@code yaml} is a workflow Turing-workflow can read, by reading it the way a run
     * would — the only judge of that is the interpreter itself.
     *
     * @param yaml the candidate text
     * @return {@code null} when it can be read and has at least one step, else why not
     */
    public static String validate(String yaml) {
        if (yaml == null || yaml.isBlank()) return "the workflow is empty";
        try {
            Interpreter probe = new Interpreter();
            probe.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            MatrixCode code = probe.getCode();
            if (code == null || code.getTransitions() == null || code.getTransitions().isEmpty()) {
                return "the workflow has no steps";
            }
            return null;
        } catch (Exception e) {
            String m = e.getMessage();
            return "not a workflow Turing-workflow can read: "
                    + (m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
        }
    }

    /**
     * Writes {@code yaml} as the project's own workflow {@code name}, creating or replacing the
     * file under {@code workflows/} in the working directory.
     *
     * <p>Writing over a bundled name is how a bundled default is customised: the project file
     * hides the bundled one from then on. Nothing is written unless {@link #validate} passes, so
     * a file on disk is always one the interpreter can run.</p>
     *
     * @param name the basename without {@code .yaml}
     * @param yaml the text to write
     * @return the written workflow
     * @throws IllegalArgumentException when the name is unsafe, the project has no working
     *                                  directory, or the text is not a workflow — the message
     *                                  says which
     * @throws IOException              when the file cannot be written
     */
    public Document write(String name, String yaml) throws IOException {
        if (!isSafeName(name)) throw new IllegalArgumentException("not a workflow name: " + name);
        if (projectDir == null) {
            throw new IllegalArgumentException(
                    "this project has no working directory; set one before writing workflows");
        }
        String why = validate(yaml);
        if (why != null) throw new IllegalArgumentException(why);
        Files.createDirectories(projectDir);
        Path file = projectDir.resolve(name + ".yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return documentOf(name, yaml, ORIGIN_PROJECT, true);
    }

    /** @return the document, with the inputs {@link #paramsOf} finds in its text */
    private static Document documentOf(String name, String yaml, String origin, boolean editable) {
        return new Document(name, yaml, origin, editable, paramsOf(yaml));
    }

    /**
     * The inputs a workflow asks a run to give it.
     *
     * <p>A workflow declares them in its {@code params} section, and the form is drawn from that
     * declaration rather than inferred ({@code WorkflowInputParams_260701_oo01}). A workflow with
     * no such section — every workflow written before the convention — still refers to
     * <code>${key}</code> in its body, so those are read out of the text instead, each as a
     * required one-line string. <code>${result}</code> is the previous transition's result, not an
     * input, so it is never one of them.</p>
     *
     * @param yaml the workflow's whole text
     * @return the inputs, declared ones in declared order, else scanned ones in the order they
     *         appear; empty when there are none or the text cannot be read as YAML
     */
    public static List<ParamSpec> paramsOf(String yaml) {
        if (yaml == null || yaml.isBlank()) return List.of();
        List<ParamSpec> declared = declaredParams(yaml);
        return declared.isEmpty() ? scannedParams(yaml) : declared;
    }

    /** @return the {@code params} section read as YAML, or empty when there is none */
    private static List<ParamSpec> declaredParams(String yaml) {
        Object params;
        try {
            Object doc = new Yaml().load(yaml);
            params = doc instanceof Map<?, ?> m ? m.get("params") : null;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not read the params section", e);
            return List.of();
        }
        if (!(params instanceof Map<?, ?> declared)) return List.of();
        List<ParamSpec> out = new ArrayList<>();
        for (Map.Entry<?, ?> e : declared.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (key.isBlank()) continue;
            out.add(specOf(key, e.getValue()));
        }
        return List.copyOf(out);
    }

    /**
     * @param declaration the value under the key: the six fields as a map, or a bare string, which
     *                    older workflows use for the description alone
     */
    private static ParamSpec specOf(String key, Object declaration) {
        if (!(declaration instanceof Map<?, ?> m)) {
            String description = declaration == null ? "" : String.valueOf(declaration);
            return new ParamSpec(key, null, description, "text", true, null, List.of());
        }
        String label = text(m.get("label"));
        String description = text(m.get("description"));
        String type = text(m.get("type"));
        String defaultValue = m.get("default") == null ? null : String.valueOf(m.get("default"));
        Object required = m.get("required");
        boolean isRequired = required == null
                ? defaultValue == null
                : Boolean.parseBoolean(String.valueOf(required));
        List<String> options = new ArrayList<>();
        if (m.get("options") instanceof List<?> declared) {
            for (Object o : declared) options.add(String.valueOf(o));
        }
        return new ParamSpec(key,
                label == null || label.isEmpty() ? null : label,
                description == null ? "" : description,
                type == null || type.isEmpty() ? "text" : type,
                isRequired, defaultValue, List.copyOf(options));
    }

    /**
     * @return the <code>${key}</code> the text carries, in the order they appear, each once,
     *         without the result. {@code state.getString('key')} is deliberately not scanned: a
     *         workflow reads its own state that way too — what {@code keepWorkerReply} put there —
     *         and asking a person to fill those in is wrong ({@code ChainedRolesInAPlan_260913_oo01})
     */
    private static List<ParamSpec> scannedParams(String yaml) {
        Map<String, ParamSpec> byKey = new LinkedHashMap<>();
        var m = PLACEHOLDER.matcher(yaml);
        while (m.find()) {
            String key = m.group(1).strip();
            if (key.isEmpty() || RESULT_VARIABLE.equals(key) || byKey.containsKey(key)) continue;
            byKey.put(key, new ParamSpec(key, null, "", "text", true, null, List.of()));
        }
        return List.copyOf(byKey.values());
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).strip();
    }

    /** @return the project's own workflows, basename to text; empty when it has no directory */
    private Map<String, String> projectFiles() {
        Map<String, String> out = new LinkedHashMap<>();
        if (projectDir == null || !Files.isDirectory(projectDir)) return out;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(projectDir)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".yaml"))
             .forEach(files::add);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list " + projectDir, e);
            return out;
        }
        Collections.sort(files);
        for (Path f : files) {
            String base = stripYaml(f.getFileName().toString());
            if (!isSafeName(base)) continue;
            try {
                out.put(base, Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not read " + f, e);
            }
        }
        return out;
    }

    /**
     * @return the bundled workflows, basename to text, from every classpath root that has a
     *         {@code workflows} directory. Deployed there is one root, the uber-jar; under a test
     *         there are two, {@code target/test-classes} and {@code target/classes}, and asking for
     *         the one directory ({@code getResource}) would see only the first. Roots are read in
     *         classpath order and the first file of a name wins — the same file
     *         {@link #read} finds through {@code getResourceAsStream}. A directory on disk and an
     *         entry inside a jar are walked the same way, through a {@link FileSystem}.
     */
    private Map<String, String> bundledFiles() {
        Map<String, String> out = new LinkedHashMap<>();
        List<URL> roots;
        try {
            roots = Collections.list(getClass().getClassLoader().getResources(BUNDLED_DIR.substring(1)));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not find bundled workflows", e);
            return out;
        }
        for (URL dirUrl : roots) {
            try {
                URI uri = dirUrl.toURI();
                if ("jar".equals(uri.getScheme())) {
                    String[] parts = uri.toString().split("!", 2);
                    URI jarUri = URI.create(parts[0]);
                    FileSystem fs;
                    boolean opened = false;
                    try {
                        fs = FileSystems.newFileSystem(jarUri, Map.of());
                        opened = true;
                    } catch (FileSystemAlreadyExistsException e) {
                        fs = FileSystems.getFileSystem(jarUri);
                    }
                    try {
                        readYamlsIn(fs.getPath(parts[1]), out);
                    } finally {
                        if (opened) fs.close();
                    }
                } else {
                    readYamlsIn(Path.of(uri), out);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not list bundled workflows at " + dirUrl, e);
            }
        }
        return out;
    }

    /** Adds the {@code .yaml} files of {@code dir} to {@code out}, keeping any name already there. */
    private static void readYamlsIn(Path dir, Map<String, String> out) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".yaml")).forEach(files::add);
        }
        files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path f : files) {
            String base = stripYaml(f.getFileName().toString());
            if (isSafeName(base) && !out.containsKey(base)) {
                out.put(base, Files.readString(f, StandardCharsets.UTF_8));
            }
        }
    }

    private static Entry entryOf(String name, String yaml, String origin) {
        var m = NAME_LINE.matcher(yaml);
        String title = m.find() ? unquote(m.group(1)) : name;
        String description = firstDescriptionLine(yaml);
        return new Entry(name, title, description, origin, ORIGIN_PROJECT.equals(origin));
    }

    /**
     * The first line of a {@code description:} field, whether it is inline or a {@code |} block.
     */
    static String firstDescriptionLine(String yaml) {
        var m = DESCRIPTION_LINE.matcher(yaml);
        if (!m.find()) return "";
        String inline = m.group(1);
        if (!inline.isEmpty() && !inline.equals("|") && !inline.equals(">")
                && !inline.equals("|-") && !inline.equals(">-")) {
            return unquote(inline);
        }
        String rest = yaml.substring(m.end());
        for (String line : rest.split("\n")) {
            if (line.isBlank()) continue;
            if (!Character.isWhitespace(line.charAt(0))) break;
            return line.strip();
        }
        return "";
    }

    private static boolean matches(Entry e, String yaml, String q) {
        if (q.isEmpty()) return true;
        return e.name().toLowerCase(Locale.ROOT).contains(q)
                || e.title().toLowerCase(Locale.ROOT).contains(q)
                || e.description().toLowerCase(Locale.ROOT).contains(q)
                || yaml.toLowerCase(Locale.ROOT).contains(q);
    }

    private static String stripYaml(String fileName) {
        return fileName.endsWith(".yaml") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
