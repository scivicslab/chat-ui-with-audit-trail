package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.rest.TokenUsage;

/**
 * The tokens of a Claude Code call reach the conversation on its {@code result} event. The lines are modelled
 * on the CLI's own stream-json: each assistant line carries its model request's {@code usage}, and the
 * {@code result} line carries the output summed over the call.
 */
class ClaudeCodeTokenUsageTest {

    private static final String FIRST_REQUEST = """
            {"type":"assistant","message":{"id":"msg_1","role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{}}],"usage":{"input_tokens":2,"cache_creation_input_tokens":30066,"cache_read_input_tokens":11853,"output_tokens":365}},"session_id":"s1"}""";
    private static final String SECOND_REQUEST = """
            {"type":"assistant","message":{"id":"msg_2","role":"assistant","content":[{"type":"text","text":"done"}],"usage":{"input_tokens":3,"cache_creation_input_tokens":100,"cache_read_input_tokens":41919,"output_tokens":50}},"session_id":"s1"}""";
    private static final String RESULT = """
            {"type":"result","subtype":"success","is_error":false,"duration_ms":1200,"result":"done","session_id":"s1","total_cost_usd":0.05,"usage":{"input_tokens":5,"cache_creation_input_tokens":30166,"cache_read_input_tokens":53772,"output_tokens":415}}""";
    private static final String RESULT_WITHOUT_USAGE = """
            {"type":"result","subtype":"success","is_error":false,"duration_ms":1,"result":"x","session_id":"s1"}""";

    private static List<ChatEvent> run(String... lines) throws Exception {
        Path dir = Files.createTempDirectory("provider-usage-test");
        ClaudeCodeProvider provider = new ClaudeCodeProvider(
                new HarnessSettings("default", dir, 1, "sonnet", null, dir), "p", "c", dir,
                com.scivicslab.chatui.agent.ToolSet.FULL);
        StreamEventParser parser = new StreamEventParser();
        List<ChatEvent> sent = new ArrayList<>();
        for (String line : lines) {
            for (StreamEvent e : parser.parse(line)) provider.dispatch(e, sent::add, new boolean[1], new boolean[1]);
        }
        return sent;
    }

    private static ChatEvent resultOf(List<ChatEvent> sent) {
        return sent.stream().filter(e -> "result".equals(e.type())).findFirst().orElseThrow();
    }

    @Test
    void anAssistantLinesUsageIsReadWithTheFourCounts() {
        StreamEvent first = new StreamEventParser().parse(FIRST_REQUEST).get(0);
        assertEquals(new TokenUsage(2, 30066, 11853, 365), first.usage());
        assertEquals(41_921, first.usage().total());
    }

    @Test
    void theResultIsRecordedWithTheFirstRequestsInputAndTheCallsOutput() throws Exception {
        TokenUsage usage = resultOf(run(FIRST_REQUEST, SECOND_REQUEST, RESULT)).usage();
        assertEquals(new TokenUsage(2, 30066, 11853, 415), usage);
        assertEquals(41_921, usage.total());
    }

    @Test
    void aResultWithoutAssistantUsageKeepsTheResultLinesOwn() throws Exception {
        assertEquals(new TokenUsage(5, 30166, 53772, 415), resultOf(run(RESULT)).usage());
    }

    @Test
    void aCallThatReportsNoUsageHasNone() throws Exception {
        assertNull(resultOf(run(RESULT_WITHOUT_USAGE)).usage());
    }

    @Test
    void theNextCallDoesNotInheritTheFirstRequestOfTheLast() throws Exception {
        List<ChatEvent> results = run(FIRST_REQUEST, RESULT, RESULT_WITHOUT_USAGE).stream()
                .filter(e -> "result".equals(e.type())).toList();
        assertEquals(new TokenUsage(2, 30066, 11853, 415), results.get(0).usage());
        assertNull(results.get(1).usage());
    }
}
