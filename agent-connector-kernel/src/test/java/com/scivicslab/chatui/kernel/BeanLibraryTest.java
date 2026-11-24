package com.scivicslab.chatui.kernel;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent-connector-kernel is a library of CDI beans for the Quarkus applications that use it (tasks/todo.md, plan
 * change 2026-11-22, Decision 12, step 1). Quarkus discovers beans in a dependency only when the dependency has a
 * Jandex index at META-INF/jandex.idx or a beans.xml (https://quarkus.io/guides/cdi-reference, "Bean Discovery"), so
 * the build must write the index.
 */
class BeanLibraryTest {

    @Test
    void theBuildWritesAJandexIndex() throws Exception {
        URL index = getClass().getClassLoader().getResource("META-INF/jandex.idx");
        assertNotNull(index, "META-INF/jandex.idx is not on agent-connector-kernel's class path");
        try (InputStream in = index.openStream()) {
            assertTrue(in.readAllBytes().length > 0, "the index is empty");
        }
    }
}
