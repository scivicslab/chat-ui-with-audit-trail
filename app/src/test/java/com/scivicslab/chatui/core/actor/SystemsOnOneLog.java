package com.scivicslab.chatui.core.actor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.scivicslab.chatui.core.iolog.IoLogStore;

/**
 * Actor systems that start one after another on the same I/O log, the way a restart does.
 *
 * <p>The log's database lives in a {@code @TempDir}. JUnit deletes that directory as soon as the
 * test returns, and the database and the systems' schedulers kept writing into it while JUnit
 * deleted it, so the deletion failed now and then with {@code DirectoryNotEmptyException}.
 * {@link #close()} stops every system's watchers and closes the database before JUnit gets there.
 */
final class SystemsOnOneLog implements AutoCloseable {

    final IoLogStore log = new IoLogStore();
    private final List<ChatUiActorSystem> started = new ArrayList<>();

    SystemsOnOneLog(Path database) {
        log.useDatabaseAt(database.toString());
    }

    /** @return a newly initialised system on this log */
    ChatUiActorSystem start() {
        return start(s -> { });
    }

    /**
     * @param beforeInit what to do to the system before its start-up, e.g. add a provider kind a recorded
     *                   conversation is on, so its restore finds it
     * @return a newly initialised system on this log
     */
    ChatUiActorSystem start(java.util.function.Consumer<ChatUiActorSystem> beforeInit) {
        ChatUiActorSystem system = new ChatUiActorSystem();
        beforeInit.accept(system);
        system.ioLogStore = log;
        // What reads the settings back on start-up; without it a restarted system restores no project settings.
        system.ioLogView = com.scivicslab.chatui.core.iolog.IoLogView.over(log);
        system.init();
        started.add(system);
        return system;
    }

    @Override
    public void close() throws Exception {
        for (ChatUiActorSystem system : started) {
            system.stopProjectWatchers();
            system.stopActivityWatcher();
        }
        log.store().close();
    }
}
