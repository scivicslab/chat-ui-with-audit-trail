package com.scivicslab.sshtools;

import java.time.Duration;

/**
 * The limits of one {@code ssh} run (tasks/todo.md, "Stage 11 spec"). When a run reaches one, the process is killed
 * and the tool answers with an error naming it.
 *
 * @param time        how long one run may take
 * @param outputBytes how many bytes one output stream may carry
 * @param fileBytes   how large a file {@code upload_file} sends or {@code download_file} writes may be
 */
public record SshLimits(Duration time, long outputBytes, long fileBytes) {

    /** 120 s, 1 MiB per stream, 64 MiB per file. */
    public static final SshLimits DEFAULT = new SshLimits(Duration.ofSeconds(120), 1L << 20, 64L << 20);
}
