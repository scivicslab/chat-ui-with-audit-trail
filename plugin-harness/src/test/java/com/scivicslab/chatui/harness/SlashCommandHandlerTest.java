package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.rest.ChatEvent;

/**
 * What each command a person types changes, and what it shows them.
 * See {@code SlashCommandsAndClearButton_260929_oo01}.
 */
@Tag("SlashCommandsAndClearButton_260929_oo01")
@DisplayName("プロンプト入力欄の / コマンド")
class SlashCommandHandlerTest {

    /** A CliProcess that starts no `claude`: only its config and session id are read here. */
    private static final class StubCliProcess extends CliProcess {
        private String stubLastSessionId;

        StubCliProcess(CliConfig config, String lastSessionId) {
            super("stub-binary", "STUB_API_KEY", config);
            this.stubLastSessionId = lastSessionId;
        }

        @Override public String getLastSessionId() { return stubLastSessionId; }
        @Override public void clearLastSessionId() { stubLastSessionId = null; }
        @Override public void cancel() { /* no process to interrupt */ }
    }

    private StubCliProcess cliProcess;
    private SlashCommandHandler handler;
    private List<ChatEvent> shown;

    @BeforeEach
    void setUp() {
        cliProcess = new StubCliProcess(CliConfig.defaults("sonnet"), "session-before");
        handler = new SlashCommandHandler(cliProcess);
        shown = new ArrayList<>();
    }

    private String shownText() {
        return String.join("\n", shown.stream().map(ChatEvent::content).toList());
    }

    @Test
    @DisplayName("スラッシュで始まらない入力は、コマンドとして扱わない")
    void isCommand_plainText_isNotOne() {
        assertFalse(handler.isCommand("この行は質問です"));
        assertFalse(handler.isCommand(null));
        assertTrue(handler.isCommand("/help"));
    }

    @Test
    @DisplayName("/model は次の起動に渡すモデル名を変える")
    void model_withName_changesTheConfig() {
        handler.handle("/model opus", shown::add);

        assertEquals("opus", cliProcess.getConfig().model());
        assertTrue(shownText().contains("opus"));
    }

    @Test
    @DisplayName("引数なしの /model は何も変えず、いまの値を見せる")
    void model_withoutName_onlyReports() {
        handler.handle("/model", shown::add);

        assertEquals("sonnet", cliProcess.getConfig().model(), "unchanged");
        assertTrue(shownText().contains("sonnet"));
    }

    @Test
    @DisplayName("/effort は受け付ける段階だけを設定する")
    void effort_knownLevel_changesTheConfig() {
        handler.handle("/effort medium", shown::add);

        assertEquals("medium", cliProcess.getConfig().effort());
    }

    @Test
    @DisplayName("知らない段階の /effort は何も変えず、選べる値を見せる")
    void effort_unknownLevel_changesNothing() {
        handler.handle("/effort そんな段階はない", shown::add);

        assertNull(cliProcess.getConfig().effort(), "left as it was");
        assertTrue(shownText().contains("low"), shownText());
        assertTrue(shownText().contains("max"), shownText());
    }

    @Test
    @DisplayName("引数なしの /effort は、未設定を CLI の既定として見せる")
    void effort_unset_saysItIsTheCliOwnDefault() {
        handler.handle("/effort", shown::add);

        assertTrue(shownText().contains("unset"), shownText());
    }

    @Test
    @DisplayName("/clear はセッションIDを設定からも控えからも消す")
    void clear_dropsBothCopiesOfTheSessionId() {
        cliProcess.setConfig(cliProcess.getConfig().withSessionId("session-before"));

        handler.handle("/clear", shown::add);

        assertNull(cliProcess.getConfig().sessionId(), "the next run carries no --resume");
        assertNull(cliProcess.getLastSessionId(), "and nothing is kept to restore it from");
    }

    @Test
    @DisplayName("/session <id> は次の起動が再開するセッションを指定する")
    void session_withId_setsIt() {
        handler.handle("/session abc", shown::add);

        assertEquals("abc", cliProcess.getConfig().sessionId());
    }

    @Test
    @DisplayName("引数なしの /session は、控えてあるIDを見せる")
    void session_withoutId_reportsTheKeptOne() {
        handler.handle("/session", shown::add);

        assertTrue(shownText().contains("session-before"), shownText());
    }

    @Test
    @DisplayName("/help は5つのコマンドをすべて挙げる")
    void help_listsEveryCommand() {
        handler.handle("/help", shown::add);

        String text = shownText();
        for (String command : List.of("/help", "/model", "/effort", "/session", "/clear")) {
            assertTrue(text.contains(command), command + " is missing from " + text);
        }
    }

    @Test
    @DisplayName("知らないコマンドは何も変えず、エラーとして返る")
    void unknownCommand_changesNothing() {
        handler.handle("/nosuchcommand", shown::add);

        assertEquals("sonnet", cliProcess.getConfig().model(), "nothing was touched");
        assertTrue(shownText().contains("Unknown command"), shownText());
        assertEquals("error", shown.get(0).type(), "shown as an error, not as information");
    }
}
