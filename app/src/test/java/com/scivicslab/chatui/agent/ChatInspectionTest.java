package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.provider.LlmProvider.ModelEntry;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator.Entry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How the conversation-inspection tools and {@code ask_chat} word what they found. */
class ChatInspectionTest {

    private static final List<ModelEntry> BROKER_MODELS = List.of(
            new ModelEntry("google/gemma-4-26B-A4B-it", "chat", "broker"),
            new ModelEntry("qwen3.8-flash-next-tensorfold", "chat", "broker"));

    @Test
    void statusIsOneKeyValueLinePerEntryInGivenOrder() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("chat", "project1/chat-05");
        status.put("busy", false);
        status.put("model", "google/gemma-4-26B-A4B-it");
        assertEquals("chat: project1/chat-05\nbusy: false\nmodel: google/gemma-4-26B-A4B-it",
                ChatInspection.formatStatus(status));
    }

    @Test
    void modelsAreOneLineEachAndMissingFieldsShowAsDash() {
        assertEquals("a (chat, broker)\nb (-, -)",
                ChatInspection.formatModels(List.of(new ModelEntry("a", "chat", "broker"),
                        new ModelEntry("b", null, " "))));
        assertTrue(ChatInspection.formatModels(List.of()).startsWith("no models are listed"));
    }

    @Test
    void aListedModelNameMaySet() {
        assertNull(ChatInspection.refusalFor("qwen3.8-flash-next-tensorfold", BROKER_MODELS));
    }

    /** The case that cost an afternoon: the broker renamed a model, the old name 404ed on every call. */
    @Test
    void anUnlistedNameIsRefusedAndTheListIsInTheAnswer() {
        String refusal = ChatInspection.refusalFor("qwen3.8-flash-next", BROKER_MODELS);
        assertTrue(refusal.startsWith("error: 'qwen3.8-flash-next' is not one of"), refusal);
        assertTrue(refusal.contains("qwen3.8-flash-next-tensorfold"), refusal);
        assertTrue(refusal.contains("google/gemma-4-26B-A4B-it"), refusal);
    }

    @Test
    void whenTheProviderListsNothingAnyNameIsLetThrough() {
        assertNull(ChatInspection.refusalFor("anything", List.of()));
        assertNull(ChatInspection.refusalFor("anything", null));
    }

    @Test
    void aBlankNameIsRefused() {
        assertEquals("error: model is required", ChatInspection.refusalFor(" ", BROKER_MODELS));
        assertEquals("error: model is required", ChatInspection.refusalFor(null, BROKER_MODELS));
    }

    private static List<Entry> entries(int n) {
        List<Entry> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) list.add(new Entry(1_700_000_000_000L + i, "agent", "step", "line " + i));
        return list;
    }

    @Test
    void logReturnsTheNewestEntriesOldestFirst() {
        String[] lines = ChatInspection.formatLog(entries(30), 3).split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].endsWith("agent step line 28"), lines[0]);
        assertTrue(lines[2].endsWith("agent step line 30"), lines[2]);
    }

    @Test
    void logDefaultsToTwentyAndCapsAtTwoHundred() {
        assertEquals(ChatInspection.DEFAULT_LOG_ENTRIES, ChatInspection.formatLog(entries(500), null).split("\n").length);
        assertEquals(ChatInspection.DEFAULT_LOG_ENTRIES, ChatInspection.formatLog(entries(500), 0).split("\n").length);
        assertEquals(ChatInspection.MAX_LOG_ENTRIES, ChatInspection.formatLog(entries(500), 100000).split("\n").length);
    }

    @Test
    void logFlattensNewlinesAndCutsLongData() {
        List<Entry> list = List.of(new Entry(1_700_000_000_000L, "agent", "obs", "a\nb\r\nc" + "x".repeat(400)));
        String line = ChatInspection.formatLog(list, null);
        assertTrue(!line.contains("\n") && !line.contains("\r"), line);
        assertTrue(line.endsWith("…"), line);
    }

    @Test
    void emptyLogSaysSo() {
        assertEquals("the log is empty", ChatInspection.formatLog(List.of(), null));
        assertEquals("the log is empty", ChatInspection.formatLog(null, null));
    }

    @Test
    void endingLineNamesHowTheTurnEndedAndWhatItUsed() {
        assertEquals("[ended: answered; model calls: 3; tool calls: 2]",
                ChatInspection.endingLine(ChatInspection.ENDED_ANSWERED, 3, 2));
        assertEquals("[ended: step-limit; model calls: 30; tool calls: 29]",
                ChatInspection.endingLine(ChatInspection.ENDED_STEP_LIMIT, 30, 29));
    }
}
