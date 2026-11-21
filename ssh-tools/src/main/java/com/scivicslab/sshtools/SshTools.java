package com.scivicslab.sshtools;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.plugin.ToolParameter;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.pojoactor.core.ActorRef;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The seven ssh tools (tasks/todo.md, "Stage 11 spec"): {@code run_command}, {@code submit_job}, {@code job_status},
 * {@code cancel_job}, {@code list_remote}, {@code upload_file} and {@code download_file}. Each builds one remote command,
 * checks its arguments first, and runs {@code ssh} through the host's {@link SshRunner} actor. A {@link ToolExtension},
 * so agent-connector-kernel's {@code ToolRunner} and MCP server offer them.
 */
public final class SshTools implements ToolExtension {

    /** The tools' names, in the order they are listed. */
    public static final List<String> NAMES = List.of("run_command", "submit_job", "job_status", "cancel_job",
            "list_remote", "upload_file", "download_file");

    private static final Pattern SUBMITTED = Pattern.compile("Submitted batch job ([0-9]+)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ActorRef<SshRunner> runner;
    private final SshCommandLine ssh;
    private final SlurmSettings slurm;
    private final SshLimits limits;
    private final FileAccessScope files;
    private final Supplier<String> jobDirName;

    /**
     * @param runner     the host's runner
     * @param ssh        the command line to the host
     * @param slurm      the Slurm command names and jobs directory
     * @param limits     the output and file limits
     * @param files      the working directory the file tools are bound to
     * @param jobDirName makes the name of a new job's directory, for example {@link #newJobDirName()}
     */
    public SshTools(ActorRef<SshRunner> runner, SshCommandLine ssh, SlurmSettings slurm, SshLimits limits,
                    FileAccessScope files, Supplier<String> jobDirName) {
        this.runner = runner;
        this.ssh = ssh;
        this.slurm = slurm;
        this.limits = limits;
        this.files = files;
        this.jobDirName = jobDirName;
    }

    /** @return a new job directory's name: the local time and eight random hex digits, e.g. 20261122-051500-1a2b3c4d */
    public static String newJobDirName() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return LocalDateTime.now().format(STAMP) + "-" + HexFormat.of().formatHex(bytes);
    }

    /** @return the tools' definitions, in {@link #NAMES} order */
    public static List<ToolCatalog.Spec> specs() {
        return List.of(
                spec("run_command", "Run one shell command on the cluster's login node under your own account and "
                                + "return its exit status and output. One line; no line breaks.",
                        new ToolParameter("command", "string", true, "the command line for the cluster's shell")),
                spec("submit_job", "Submit a Slurm batch script with sbatch. The script is stored in a new job "
                                + "directory on the cluster; the answer is the job id.",
                        new ToolParameter("script", "string", true, "the whole batch script, with its #SBATCH lines"),
                        new ToolParameter("name", "string", false,
                                "the job's name: letters, digits, '-' and '_', up to 64 characters")),
                spec("job_status", "Show a Slurm job's state, from squeue while it is queued or running and from "
                                + "sacct after it ended.",
                        new ToolParameter("job_id", "string", true, "the job id, such as 4242 or 4242_3")),
                spec("cancel_job", "Cancel a Slurm job with scancel.",
                        new ToolParameter("job_id", "string", true, "the job id, such as 4242 or 4242_3")),
                spec("list_remote", "List a directory or file on the cluster with ls -la.",
                        new ToolParameter("path", "string", true, "the path on the cluster")),
                spec("upload_file", "Copy a file from the working directory on this computer to the cluster.",
                        new ToolParameter("local_path", "string", true, "the file, relative to the working directory"),
                        new ToolParameter("remote_path", "string", true, "where to write it on the cluster")),
                spec("download_file", "Copy a file from the cluster into the working directory on this computer.",
                        new ToolParameter("remote_path", "string", true, "the file on the cluster"),
                        new ToolParameter("local_path", "string", true,
                                "where to write it, relative to the working directory"),
                        new ToolParameter("overwrite", "boolean", false,
                                "replace an existing file; false when left out")));
    }

    private static ToolCatalog.Spec spec(String name, String description, ToolParameter... parameters) {
        return new ToolCatalog.Spec(name, description, List.of(parameters), false);
    }

    @Override
    public boolean defines(String tool) {
        return NAMES.contains(tool);
    }

    @Override
    public boolean offers(String tool) {
        return NAMES.contains(tool);
    }

    @Override
    public String run(String tool, String argumentsJson) {
        if (!NAMES.contains(tool)) return null;
        JSONObject args;
        try {
            args = new JSONObject(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            return "error: the arguments are not a JSON object";
        }
        try {
            return switch (tool) {
                case "run_command" -> runCommand(args.optString("command", ""));
                case "submit_job" -> submitJob(args.optString("script", ""), args.optString("name", ""));
                case "job_status" -> jobStatus(args.optString("job_id", ""));
                case "cancel_job" -> cancelJob(args.optString("job_id", ""));
                case "list_remote" -> listRemote(args.optString("path", ""));
                case "upload_file" -> upload(args.optString("local_path", ""), args.optString("remote_path", ""));
                default -> download(args.optString("remote_path", ""), args.optString("local_path", ""),
                        args.optBoolean("overwrite", false));
            };
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
    }

    private String runCommand(String command) {
        String refusal = RemoteText.refusal("command", command);
        if (refusal != null) return refusal;
        SshRunner.Result r = ssh(command, SshRunner.Input.none(), null, limits.outputBytes());
        if (r.limit() != null) return "error: " + r.limit();
        if (r.exitCode() == 255) return "error: ssh failed (exit 255): " + r.stderr().strip();
        StringBuilder sb = new StringBuilder("exit ").append(r.exitCode()).append('\n').append(r.stdout());
        if (!r.stderr().isEmpty()) sb.append("\n[stderr]\n").append(r.stderr());
        return sb.toString();
    }

    private String submitJob(String script, String name) {
        if (script.isEmpty()) return "error: script is empty";
        String refusal = JobArguments.nameRefusal(name);
        if (refusal != null) return refusal;
        String dir = slurm.jobsDir() + "/" + jobDirName.get();
        String file = dir + "/job.sh";
        String command = "mkdir -p -- " + RemoteText.quote(dir) + " && cat > " + RemoteText.quote(file) + " && "
                + slurm.sbatch() + (name.isEmpty() ? "" : " --job-name=" + RemoteText.quote(name)) + " "
                + RemoteText.quote(file);
        SshRunner.Result r = ssh(command, SshRunner.Input.text(script), null, limits.outputBytes());
        String failure = failure("sbatch", r);
        if (failure != null) return failure;
        Matcher m = SUBMITTED.matcher(r.stdout());
        if (!m.find()) return "error: sbatch did not report a job id: " + r.stdout().strip() + " " + r.stderr().strip();
        return "job_id: " + m.group(1) + "\njob_dir: " + dir + "\n";
    }

    private String jobStatus(String jobId) {
        String refusal = JobArguments.jobIdRefusal(jobId);
        if (refusal != null) return refusal;
        SshRunner.Result queued = ssh(slurm.squeue() + " -j " + jobId + " -h -o '%i|%j|%T|%M|%R'",
                SshRunner.Input.none(), null, limits.outputBytes());
        if (queued.limit() != null) return "error: " + queued.limit();
        if (queued.exitCode() == 0 && !queued.stdout().isBlank()) {
            return "squeue (JOBID|NAME|STATE|TIME|REASON):\n" + queued.stdout();
        }
        SshRunner.Result ended = ssh(slurm.sacct() + " -j " + jobId + " -n -P -o JobID,JobName,State,ExitCode,Elapsed",
                SshRunner.Input.none(), null, limits.outputBytes());
        String failure = failure("sacct", ended);
        if (failure != null) return failure;
        if (ended.stdout().isBlank()) return "error: job " + jobId + " is known neither to squeue nor to sacct";
        return "sacct (JobID|JobName|State|ExitCode|Elapsed):\n" + ended.stdout();
    }

    private String cancelJob(String jobId) {
        String refusal = JobArguments.jobIdRefusal(jobId);
        if (refusal != null) return refusal;
        SshRunner.Result r = ssh(slurm.scancel() + " " + jobId, SshRunner.Input.none(), null, limits.outputBytes());
        String failure = failure("scancel", r);
        return failure != null ? failure : "cancelled " + jobId + (r.stdout().isBlank() ? "" : "\n" + r.stdout());
    }

    private String listRemote(String path) {
        String refusal = RemoteText.refusal("path", path);
        if (refusal != null) return refusal;
        SshRunner.Result r = ssh("ls -la -- " + RemoteText.quote(path), SshRunner.Input.none(), null,
                limits.outputBytes());
        String failure = failure("ls", r);
        return failure != null ? failure : r.stdout();
    }

    private String upload(String localPath, String remotePath) {
        String refusal = RemoteText.refusal("remote_path", remotePath);
        if (refusal != null) return refusal;
        Path local = LocalFiles.readable(files, localPath);
        long size;
        try {
            size = Files.size(local);
        } catch (IOException e) {
            return "error: cannot read local_path " + localPath + ": " + e.getMessage();
        }
        if (size > limits.fileBytes()) {
            return "error: local_path " + localPath + " is " + size + " bytes, over the limit of " + limits.fileBytes()
                    + " bytes";
        }
        SshRunner.Result r = ssh("cat > " + RemoteText.quote(remotePath), SshRunner.Input.file(local), null,
                limits.outputBytes());
        String failure = failure("cat", r);
        return failure != null ? failure : "uploaded " + size + " bytes to " + remotePath;
    }

    private String download(String remotePath, String localPath, boolean overwrite) {
        String refusal = RemoteText.refusal("remote_path", remotePath);
        if (refusal != null) return refusal;
        Path target = LocalFiles.writable(files, localPath, overwrite);
        Path tmp;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".part");
        } catch (IOException e) {
            return "error: cannot write local_path " + localPath + ": " + e.getMessage();
        }
        try {
            SshRunner.Result r = ssh("cat -- " + RemoteText.quote(remotePath), SshRunner.Input.none(), tmp,
                    limits.fileBytes());
            String failure = failure("cat", r);
            if (failure != null) return failure;
            if (overwrite) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(tmp, target);
            }
            return "downloaded " + r.stdoutBytes() + " bytes to " + files.writeRoot().relativize(target);
        } catch (IOException e) {
            return "error: cannot write local_path " + localPath + ": " + e;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                // left behind; it is a dot file next to the target
            }
        }
    }

    /** @return an {@code error: } text when the run hit a limit or the command failed, otherwise {@code null} */
    private static String failure(String what, SshRunner.Result r) {
        if (r.limit() != null) return "error: " + r.limit();
        if (r.exitCode() == 0) return null;
        String detail = (r.stderr().strip() + " " + r.stdout().strip()).strip();
        return "error: " + what + " failed (exit " + r.exitCode() + ")" + (detail.isEmpty() ? "" : ": " + detail);
    }

    private SshRunner.Result ssh(String remoteCommand, SshRunner.Input input, Path stdoutFile, long stdoutLimit) {
        List<String> command = ssh.arguments(remoteCommand);
        return runner.ask(r -> r.run(command, input, stdoutFile, stdoutLimit)).join();
    }
}
