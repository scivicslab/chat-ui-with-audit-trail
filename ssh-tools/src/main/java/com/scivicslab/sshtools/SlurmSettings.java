package com.scivicslab.sshtools;

/**
 * The deployment's Slurm command names and jobs directory (tasks/todo.md, "Stage 11 spec"). They are put into remote
 * commands as they are, so they come from ssh-connector's configuration and never from the model.
 *
 * @param sbatch  the submit command
 * @param squeue  the queue command
 * @param sacct   the accounting command
 * @param scancel the cancel command
 * @param jobsDir the directory on the cluster each job's directory is made in, absolute or relative to the home
 */
public record SlurmSettings(String sbatch, String squeue, String sacct, String scancel, String jobsDir) {

    /** The plain command names, and {@code ssh-connector/jobs} under the home directory. */
    public static final SlurmSettings DEFAULT = new SlurmSettings("sbatch", "squeue", "sacct", "scancel",
            "ssh-connector/jobs");
}
