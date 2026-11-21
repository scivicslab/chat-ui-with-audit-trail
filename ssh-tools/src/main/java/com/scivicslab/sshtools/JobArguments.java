package com.scivicslab.sshtools;

import java.util.regex.Pattern;

/**
 * The patterns the Slurm tools check their arguments against before anything runs (tasks/todo.md, "Stage 11 spec").
 */
public final class JobArguments {

    private static final Pattern JOB_ID = Pattern.compile("[0-9]+(_[0-9]+)?");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private JobArguments() {
    }

    /**
     * @return {@code null} when {@code jobId} matches {@code ^[0-9]+(_[0-9]+)?$}, otherwise an {@code error: } text
     */
    public static String jobIdRefusal(String jobId) {
        if (jobId != null && JOB_ID.matcher(jobId).matches()) return null;
        return "error: job_id must be a Slurm job id such as 4242 or 4242_3, not '" + jobId + "'";
    }

    /**
     * @return {@code null} when {@code name} is absent or empty, or is letters, digits, {@code -} and {@code _}, up to
     *         64 characters; otherwise an {@code error: } text
     */
    public static String nameRefusal(String name) {
        if (name == null || name.isEmpty() || NAME.matcher(name).matches()) return null;
        return "error: name may hold only letters, digits, '-' and '_', up to 64 characters";
    }
}
