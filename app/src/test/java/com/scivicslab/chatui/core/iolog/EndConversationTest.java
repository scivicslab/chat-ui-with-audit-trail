package com.scivicslab.chatui.core.iolog;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.PlanRunner;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.plugins.logdb.SessionStatus;
import com.scivicslab.turingworkflow.plugins.logdb.SessionSummary;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ending a conversation a job is about to remove.
 *
 * <p>Removing a conversation's actor leaves its session running in the I/O log, and
 * {@code reopenRecordedConversationSquads} builds a ConversationSquad for every running session at start-up — so the
 * conversation comes back at the next restart. A job that means to be rid of one ends its session
 * first ({@code RemovingActorsFromAWorkflow_260913_oo01}). What it wrote stays in the database,
 * where the log search still finds it.</p>
 */
class EndConversationTest {

    /** Nothing is asked of it here; a ChatSession needs one to exist. */
    private static final class SilentProvider implements LlmProvider {
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<LlmProvider.ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                                         ProviderContext ctx) {}
    }

    @TempDir
    Path tempDir;

    private IIActorSystem system;
    private IoLogStore ioLog;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("end-conversation-test");
        ioLog = new IoLogStore();
        ioLog.dbPath = tempDir.resolve("end-conversation-test").toString();
        ioLog.httpPort = 28098;
        ioLog.compress = false;
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    /** One conversation, wired the way ChatUiActorSystem.createChat wires it. */
    private String conversation(String projectId, String chatId) {
        String chatName = ChatUiActorSystem.chatActorName(projectId, chatId);
        ChatSessionIIAR iiar = new ChatSessionIIAR(chatName + ".chat", new SilentProvider(),
                Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        iiar.tellNow(a -> ((ChatSession) a).setChatIdentity(projectId, chatId)).join();
        return chatName;
    }

    private PlanRunner runner() {
        return new PlanRunner("project1.plan", system, null);
    }

    @Test
    void endingAConversationEndsItsRecordedSessionButKeepsWhatItWrote() {
        String chatName = conversation("project1", "t1");
        long sessionId = ioLog.ensureSession(chatName);
        ioLog.record(sessionId, chatName + ".chat", "turn1/conversation", "QUESTION:\nhello");

        ActionResult result = runner().endConversation(chatName);

        assertTrue(result.isSuccess(), result.getResult());
        SessionSummary session = ioLog.store().listSessions(50).stream()
                .filter(s -> s.getSessionId() == sessionId).findFirst().orElseThrow();
        assertEquals(SessionStatus.COMPLETED, session.getStatus(),
                "an ended session is not reopened as a ConversationSquad at the next start-up");
        assertFalse(ioLog.resumableConversationSquads().contains(chatName),
                "so the conversation stays gone: " + ioLog.resumableConversationSquads());
        assertTrue(session.getTotalLogEntries() > 0, "and what it wrote is still in the database");
    }

    @Test
    void anotherConversationIsLeftAsItWas() {
        String ending = conversation("project1", "t1");
        String keeping = conversation("project1", "01");
        ioLog.ensureSession(ending);
        ioLog.ensureSession(keeping);

        runner().endConversation(ending);

        assertTrue(ioLog.resumableConversationSquads().contains(keeping),
                "the one that was not named is still reopened: " + ioLog.resumableConversationSquads());
    }

    /**
     * The case a restart produces: the session is in the database, this process never opened it.
     *
     * <p>A ConversationSquad that came back through {@code resumableConversationSquads} and was never spoken to has no session
     * in this process's own map. Ending only what this process opened left such a session running,
     * so the removed conversation was built again at the next start-up — which is what the first
     * live run of the removal job did.</p>
     */
    @Test
    void aSessionThisProcessNeverOpenedIsEndedToo() {
        String chatName = ChatUiActorSystem.chatActorName("project1", "t2");
        IoLogStore before = new IoLogStore();
        before.dbPath = ioLog.dbPath;
        before.httpPort = ioLog.httpPort;
        before.compress = false;
        long sessionId = before.ensureSession(chatName);
        before.record(sessionId, chatName + ".chat", "turn1/conversation", "QUESTION:\nhello");
        before.shutdown();

        conversation("project1", "t2");
        assertTrue(ioLog.resumableConversationSquads().contains(chatName),
                "the ConversationSquad is one a restart would build: " + ioLog.resumableConversationSquads());

        ActionResult result = runner().endConversation(chatName);

        assertTrue(result.isSuccess(), result.getResult());
        assertFalse(ioLog.resumableConversationSquads().contains(chatName),
                "and is not built again: " + ioLog.resumableConversationSquads());
    }

    @Test
    void aConversationThatIsNotThereIsSaidSoRatherThanPassedOver() {
        ActionResult result = runner().endConversation("project1/chat-nobody");

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("project1/chat-nobody"), result.getResult());
    }
}
