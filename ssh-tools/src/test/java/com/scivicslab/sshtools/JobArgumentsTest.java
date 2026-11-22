package com.scivicslab.sshtools;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The {@code job_id} and {@code name} patterns (tasks/todo.md, "Stage 11 spec"). */
class JobArgumentsTest {

    @Test
    void jobIds() {
        for (String ok : List.of("1", "4242", "4242_7")) assertNull(JobArguments.jobIdRefusal(ok), ok);
        for (String bad : Arrays.asList(null, "", "12a", "_1", "1_", "1_2_3", "1 2", "-1", "1;id", "$(id)", "1\n2")) {
            assertNotNull(JobArguments.jobIdRefusal(bad), String.valueOf(bad));
        }
    }

    @Test
    void names() {
        String longest = "a".repeat(64);
        for (String ok : Arrays.asList(null, "", "job-1_A", longest)) assertNull(JobArguments.nameRefusal(ok), ok);
        for (String bad : List.of(longest + "a", "a b", "a/b", "$(id)", "a;b", "é", "a.b", "a\nb")) {
            assertNotNull(JobArguments.nameRefusal(bad), bad);
        }
    }
}
