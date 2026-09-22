package com.scivicslab.chatui.core.iolog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Searching every recorded conversation at once ({@code CrossConversationLogSearch_260913_oo01}).
 *
 * <p>Written against a real database file, recorded through {@link IoLogStore} itself: what is
 * being held in place is that the search's SQL matches the schema and the labels this program
 * actually writes. A fixture built by the test out of its own {@code CREATE TABLE} would agree
 * with the search and say nothing about the store.</p>
 *
 * <p>No {@code @QuarkusTest}: the store's configuration is package-private fields, and nothing
 * here touches a service outside this process.</p>
 */
class IoLogSearchTest {

    @TempDir
    static Path tempDir;

    private static IoLogSearch search;

    /** The session id of the conversation whose ConversationSquad is removed below. */
    private static long removedConversationSquadSession;

    /**
     * Two conversations, recorded the way {@code ChatSession} records one, and then closed.
     *
     * <p>The second one stands for a conversation whose actor was removed: nothing is deleted when
     * {@code loader.removeChild} takes a ConversationSquad out of the registry, so in the database it is a
     * session like any other.</p>
     */
    @BeforeAll
    static void recordTwoConversations() {
        IoLogStore store = new IoLogStore();
        store.dbPath = tempDir.resolve("search-test").toString();
        store.httpPort = 28099;
        store.compress = false;

        long open = store.ensureSession("project1/chat-01");
        store.record(open, "project1/chat-01.chat", "turn1/step1/llm",
                "REQUEST:\n{\"messages\":[]}\nRESPONSE:\nJobQueueRegistry は起動時に一度だけ走ります");
        store.record(open, "project1/chat-01.chat", "turn2/step1/llm",
                "REQUEST:\n{\"messages\":[]}\nRESPONSE:\nそれは別の話です");

        removedConversationSquadSession = store.ensureSession("project1/chat-junk-2");
        store.record(removedConversationSquadSession, "project1/chat-junk-2.chat", "turn7/step3/tool",
                "TOOL:\nread_file\nOBSERVATION:\nJobQueueRegistry を消したのはこの会話です");
        store.record(removedConversationSquadSession, "project1/chat-junk-2.chat", "settings",
                "a row whose label names no turn");

        store.shutdown();

        search = new IoLogSearch();
        search.ioLog = store;
    }

    @Test
    void aWordFoundInAnyConversationIsFoundInAllOfThem() {
        IoLogSearch.Result result = search.search("JobQueueRegistry", 10);

        assertEquals("", result.error());
        assertEquals(2, result.hits().size(), "one in each conversation");
        assertFalse(result.limited());
        List<String> conversations = result.hits().stream().map(IoLogSearch.Hit::conversation).toList();
        assertTrue(conversations.contains("project1/chat-01"), conversations.toString());
        assertTrue(conversations.contains("project1/chat-junk-2"),
                "the conversation whose ConversationSquad was removed is still searchable: " + conversations);
    }

    @Test
    void aHitSaysWhichTurnToOpen() {
        IoLogSearch.Hit hit = search.search("消したのはこの会話", 10).hits().get(0);

        assertEquals(removedConversationSquadSession, hit.sessionId());
        assertEquals(7, hit.turn(), "so the hit opens on the turn it was part of");
        assertEquals("turn7/step3/tool", hit.label());
        assertTrue(hit.snippet().contains("消したのはこの会話"), hit.snippet());
    }

    @Test
    void aRowThatIsNotPartOfATurnIsStillFound() {
        IoLogSearch.Hit hit = search.search("names no turn", 10).hits().get(0);

        assertEquals(-1, hit.turn(), "and says it has no turn to open");
        assertEquals("settings", hit.label());
    }

    @Test
    void moreMatchesThanAskedForAreSaidToBeMore() {
        IoLogSearch.Result result = search.search("JobQueueRegistry", 1);

        assertEquals(1, result.hits().size());
        assertTrue(result.limited(), "so the screen can say the answer is cut short");
    }

    @Test
    void anEmptyQueryFindsNothingRatherThanEverything() {
        assertTrue(search.search("   ", 10).hits().isEmpty());
    }

    @Test
    void theOverviewSaysHowMuchThereIsToSearch() {
        IoLogSearch.Overview overview = search.overview();

        assertTrue(overview.present());
        assertEquals(2, overview.conversations());
        assertEquals(4, overview.entries());
        assertFalse(overview.newest().isBlank());
    }

    @Test
    void theTurnOfALabelIsReadWholeRatherThanByItsFirstDigit() {
        assertEquals(10, IoLogSearch.turnOf("turn10/step1/llm"));
        assertEquals(1, IoLogSearch.turnOf("turn1/conversation"));
        assertEquals(-1, IoLogSearch.turnOf("turnip/step1"));
        assertEquals(-1, IoLogSearch.turnOf(null));
    }
}
