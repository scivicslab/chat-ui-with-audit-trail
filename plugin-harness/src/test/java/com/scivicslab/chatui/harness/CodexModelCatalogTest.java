package com.scivicslab.chatui.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexModelCatalogTest {

    @Test
    void modelListComesFromTheCodexAppServer(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("fake-codex-app-server");
        Files.writeString(fake, """
                #!/bin/sh
                IFS= read -r initialize
                printf '%s\\n' '{"id":1,"result":{"userAgent":"fake"}}'
                IFS= read -r model_list
                printf '%s\\n' '{"method":"account/updated","params":{"authMode":"chatgpt"}}'
                printf '%s\\n' '{"id":2,"result":{"data":[{"id":"new-default","model":"new-default","hidden":false,"isDefault":true},{"id":"another","model":"another","hidden":false,"isDefault":false}],"nextCursor":null}}'
                """);
        assertTrue(fake.toFile().setExecutable(true));

        CodexModelCatalog catalog = new CodexModelCatalog(List.of(fake.toString()),
                Duration.ofSeconds(2), Duration.ofMinutes(1));
        CodexModelCatalog.Snapshot snapshot = catalog.snapshot();

        assertEquals(List.of("new-default", "another"), snapshot.models().stream().map(m -> m.name()).toList());
        assertEquals("new-default", snapshot.defaultModel());
    }

    @Test
    void noConfiguredModelLeavesSelectionToCodex(@TempDir Path dir) {
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir, 1,
                "sonnet", null, dir);
        CodexProvider provider = new CodexProvider(settings, "p", "c", dir);

        List<String> command = provider.buildCommand();

        assertFalse(command.contains("-m"), command.toString());
        assertEquals("-", command.getLast());
    }

    @Test
    void explicitlyConfiguredModelIsStillPassedToCodex(@TempDir Path dir) {
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir, 1,
                "sonnet", "chosen-model", dir);
        CodexProvider provider = new CodexProvider(settings, "p", "c", dir);

        List<String> command = provider.buildCommand();

        int modelOption = command.indexOf("-m");
        assertTrue(modelOption >= 0, command.toString());
        assertEquals("chosen-model", command.get(modelOption + 1));
    }
}
