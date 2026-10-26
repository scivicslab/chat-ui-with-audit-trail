package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.rest.ChatEvent;

/**
 * What a person sees after a line the CLI carries out itself, such as {@code /compact}
 * ({@code SlashCommandsAndClearButton_260929_oo01}): the text of its answer, as information.
 */
@Tag("SlashCommandsAndClearButton_260929_oo01")
@DisplayName("CLI のコマンドの答え")
class ClaudeCodeCommandAnswerTest {

    @Test
    @DisplayName("本文は info になり、結果・思考・ツールの行は捨てる")
    void asCommandAnswer_keepsTheTextAsInfoAndDropsTheTurnsOtherEvents() {
        List<ChatEvent> answer = ClaudeCodeProvider.asCommandAnswer(List.of(
                ChatEvent.thinking("Thinking..."),
                ChatEvent.delta("## Context Usage"),
                ChatEvent.result("s1", 0.0, 10L, "sonnet", false)));

        assertEquals(1, answer.size(), answer.toString());
        assertEquals("info", answer.get(0).type());
        assertEquals("## Context Usage", answer.get(0).content());
    }

    @Test
    @DisplayName("コマンドが自分で出力した文字があれば、それだけが答えになる")
    void commandAnswer_prefersWhatTheCommandPrinted() {
        List<ChatEvent> replayedBefore = List.of(ChatEvent.delta("## Context Usage (replayed)"));

        List<ChatEvent> answer = ClaudeCodeProvider.commandAnswer(List.of("Compacted"), replayedBefore);

        assertEquals(1, answer.size(), answer.toString());
        assertEquals("Compacted", answer.get(0).content());
        assertEquals("info", answer.get(0).type());
    }

    @Test
    @DisplayName("出力した文字がなければ、ターンの本文が答えになる")
    void commandAnswer_fallsBackToTheTurnsText() {
        List<ChatEvent> answer = ClaudeCodeProvider.commandAnswer(List.of(), List.of(ChatEvent.delta("## Context Usage")));

        assertEquals("## Context Usage", answer.get(0).content());
    }

    @Test
    @DisplayName("CLI に送る行だけが I/O ログの対象になる")
    void isExchangeWithBackend_isTrueOnlyForTheClisOwnCommands() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("provider-test");
        ClaudeCodeProvider provider = new ClaudeCodeProvider(
                new HarnessSettings("default", dir, 1, "sonnet", null), "p", "c", dir,
                com.scivicslab.chatui.agent.ToolSet.FULL);

        assertTrue(provider.isExchangeWithBackend("/compact"));
        assertTrue(provider.isExchangeWithBackend("/context"));
        assertTrue(!provider.isExchangeWithBackend("/model"));
        assertTrue(!provider.isExchangeWithBackend("/help"));
        assertTrue(!provider.isExchangeWithBackend("この行は質問です"));
    }

    @Test
    @DisplayName("エラーはエラーのまま返る")
    void asCommandAnswer_keepsAnError() {
        List<ChatEvent> answer = ClaudeCodeProvider.asCommandAnswer(List.of(ChatEvent.error("boom")));

        assertEquals("error", answer.get(0).type());
    }

    @Test
    @DisplayName("何も出なかったときは、実行したことを伝える")
    void asCommandAnswer_saysDoneWhenTheCliPrintedNothing() {
        List<ChatEvent> answer = ClaudeCodeProvider.asCommandAnswer(List.of(
                ChatEvent.result("s1", 0.0, 10L, "sonnet", false)));

        assertEquals(1, answer.size());
        assertEquals("info", answer.get(0).type());
        assertTrue(answer.get(0).content().startsWith("Done"), answer.get(0).content());
    }
}
