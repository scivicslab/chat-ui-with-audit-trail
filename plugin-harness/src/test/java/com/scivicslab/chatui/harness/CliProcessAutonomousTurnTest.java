package com.scivicslab.chatui.harness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A prompt written while the CLI is part-way through a turn it started by itself (a background job
 * finished and the CLI began answering the notification) must receive its own answer. The CLI
 * answers the two in order and ends each with a {@code result}; the first {@code result} used to
 * end the prompt, so the prompt was answered with the notification's reply and its own answer
 * arrived one turn late, as if nobody had asked for it.
 */
class CliProcessAutonomousTurnTest {

    private static List<String> contentsUpToFirstResult(CliProcess process) throws InterruptedException {
        List<String> contents = new ArrayList<>();
        StreamEvent event;
        while ((event = process.pollTurnEvent(100)) != null) {
            if ("result".equals(event.type())) break;
            contents.add(event.content());
        }
        return contents;
    }

    @Test
    void promptWrittenDuringAnAutonomousTurn_isAnsweredByItsOwnTurn() throws Exception {
        CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", null);

        process.routeEvent(StreamEvent.text("assistant", "reply to the notification, first half"));
        process.beginTurn();
        process.routeEvent(StreamEvent.text("assistant", "reply to the notification, second half"));
        process.routeEvent(StreamEvent.result("s1", 0, 0));
        process.routeEvent(StreamEvent.text("assistant", "answer to the prompt"));
        process.routeEvent(StreamEvent.result("s1", 0, 0));

        assertEquals(List.of("answer to the prompt"), contentsUpToFirstResult(process));
    }

    @Test
    void theAutonomousTurnsRemainingEvents_stayAvailableToTheIdleMonitor() throws Exception {
        CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", null);

        process.routeEvent(StreamEvent.text("assistant", "reply to the notification, first half"));
        process.beginTurn();
        process.routeEvent(StreamEvent.text("assistant", "reply to the notification, second half"));
        process.routeEvent(StreamEvent.result("s1", 0, 0));

        List<String> autonomous = new ArrayList<>();
        StreamEvent event;
        while ((event = process.pollAutonomousEvent(0)) != null) autonomous.add(event.type());
        assertEquals(List.of("assistant", "assistant", "result"), autonomous);
    }

    @Test
    void promptWrittenWhileIdle_isStillAnsweredByItsOwnTurn() throws Exception {
        CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY", null);

        process.routeEvent(StreamEvent.text("assistant", "reply to a finished notification"));
        process.routeEvent(StreamEvent.result("s1", 0, 0));
        process.beginTurn();
        process.routeEvent(StreamEvent.text("assistant", "answer to the prompt"));
        process.routeEvent(StreamEvent.result("s1", 0, 0));

        assertEquals(List.of("answer to the prompt"), contentsUpToFirstResult(process));
    }
}
