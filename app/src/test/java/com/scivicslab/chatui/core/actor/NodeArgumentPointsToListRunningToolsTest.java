package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every tool that takes another instance's node name tells the model where to find it: call list_running_tools
 * first (ListRunningToolsShowsNodeNames_261119_oo01, achieved state 7).
 */
@Tag("ListRunningToolsShowsNodeNames_261119_oo01")
@DisplayName("The node argument's description points to list_running_tools")
class NodeArgumentPointsToListRunningToolsTest {

    static final String PHRASE = "to find the node name of another instance, call list_running_tools first";

    @ParameterizedTest
    @ValueSource(strings = {"ask_chat", "ask_chat_urgent", "cancel_request", "expect_report", "set_collaborator"})
    void theDescriptionSaysToCallListRunningToolsFirst(String tool) {
        String description = ChatSession.descriptionOf(tool);
        assertNotNull(description, tool);
        String oneLine = description.replaceAll("\\s+", " ");
        assertTrue(oneLine.contains(PHRASE), tool + ": " + oneLine);
    }
}
