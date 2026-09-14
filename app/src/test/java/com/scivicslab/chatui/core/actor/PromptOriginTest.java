package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which of the four ways a prompt arrived, as the screen says it.
 *
 * <p>A conversation takes prompts from a person at the screen, from a program calling the REST
 * endpoint, from another conversation through {@code ask_chat}, and from a workflow. Reading the
 * conversation afterwards, all four look the same — and one of them is a person's own work being
 * done on their behalf, which is the whole reason the workflow exists. The queue already carries
 * where each prompt came from; this turns that into what a reader sees
 * ({@code PromptOriginOnScreen_260915_oo01}).</p>
 */
@DisplayName("ChatSession — where a prompt came from")
class PromptOriginTest {

    @Test
    void aPersonAtTheScreen() {
        assertEquals("screen", ChatSession.originOf("screen"));
    }

    /** The REST endpoint says nothing about itself, and a program calling it is not a person. */
    @Test
    void aProgramCallingTheRestEndpoint() {
        assertEquals("api", ChatSession.originOf("human"));
        assertEquals("api", ChatSession.originOf(null));
        assertEquals("api", ChatSession.originOf(""));
    }

    @Test
    void anotherConversation() {
        assertEquals("chat project1/chat-01", ChatSession.originOf("agent:ask_chat:project1/chat-01"));
    }

    /** A plan asks through the same ask_chat path; its name is a job's, not a conversation's. */
    @Test
    void aWorkflow() {
        assertEquals("workflow project1/job-01", ChatSession.originOf("agent:ask_chat:project1/job-01"));
        assertEquals("workflow", ChatSession.originOf("agent:workflow"));
    }

    /** Anything else is shown as it was recorded rather than guessed at. */
    @Test
    void somethingElse() {
        assertEquals("agent:localhost:28900", ChatSession.originOf("agent:localhost:28900"));
    }
}
