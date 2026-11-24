package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.plugin.ToolParameter;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog keeps every built-in tool's arguments as the hand-written table had them: the same order and the
 * same required ones (ToolCatalogAndChatUiMcpServer_261120_oo01, achieved state 3). The table below was read
 * from the hand-written descriptions before they were replaced.
 */
@Tag("ToolCatalogAndChatUiMcpServer_261120_oo01")
@DisplayName("ToolCatalog keeps each tool's arguments and makes both forms from one definition")
class ToolCatalogTest {

    /** tool -> [signature, required], as the hand-written descriptions had them */
    static final Map<String, List<List<String>>> BEFORE = Map.ofEntries(
            Map.entry("ask_person", List.of(List.of("question"), List.of("question"))),
            Map.entry("read", List.of(List.of("path"), List.of("path"))),
            Map.entry("calc", List.of(List.of("expression"), List.of("expression"))),
            Map.entry("search_docs", List.of(List.of("query", "node"), List.of("query"))),
            Map.entry("list_running_tools", List.of(List.of("tool"), List.of())),
            Map.entry("list_references", List.of(List.of("id", "direction", "relation", "node"), List.of("id"))),
            Map.entry("write", List.of(List.of("path", "content"), List.of("path"))),
            Map.entry("remove", List.of(List.of("path"), List.of("path"))),
            Map.entry("ask_chat", List.of(List.of("chatId", "prompt", "node"), List.of("chatId", "prompt"))),
            Map.entry("ask_chat_urgent", List.of(List.of("chatId", "prompt", "node"), List.of("chatId", "prompt"))),
            Map.entry("cancel_request", List.of(List.of("requestId", "node"), List.of("requestId"))),
            Map.entry("set_chat_timekeeper", List.of(List.of("chatId", "urgentWaitMinutes", "childReplyWarnMinutes"), List.of("chatId"))),
            Map.entry("accept_reply", List.of(List.of("requestId"), List.of())),
            Map.entry("expect_report", List.of(List.of("chatId", "requestId", "minutes", "node"), List.of("requestId", "minutes"))),
            Map.entry("set_chat_name", List.of(List.of("chatId", "name"), List.of("chatId"))),
            Map.entry("set_agent_loop", List.of(List.of("chatId", "yaml"), List.of("chatId", "yaml"))),
            Map.entry("load_skill", List.of(List.of("name"), List.of("name"))),
            Map.entry("set_collaborator", List.of(List.of("chatId", "role", "collaboratorChatId", "node"), List.of("chatId", "role", "collaboratorChatId"))),
            Map.entry("get_chat_status", List.of(List.of("chatId"), List.of("chatId"))),
            Map.entry("list_chat_models", List.of(List.of("chatId"), List.of("chatId"))),
            Map.entry("set_chat_model", List.of(List.of("chatId", "model"), List.of("chatId", "model"))),
            Map.entry("list_providers", List.of(List.of(), List.of())),
            Map.entry("create_chat", List.of(List.of("provider", "model", "superior", "role"), List.of())),
            Map.entry("list_chats", List.of(List.of(), List.of())),
            Map.entry("set_superior", List.of(List.of("chatId", "superiorChatId"), List.of("chatId"))),
            Map.entry("set_chat_provider", List.of(List.of("chatId", "provider"), List.of("chatId", "provider"))),
            Map.entry("delete_chat", List.of(List.of("chatId"), List.of("chatId"))),
            Map.entry("get_chat_log", List.of(List.of("chatId", "count"), List.of("chatId"))),
            Map.entry("get_request_messages", List.of(List.of("chatId"), List.of("chatId"))),
            Map.entry("set_request_messages", List.of(List.of("chatId", "messages"), List.of("chatId", "messages"))),
            Map.entry("complete_messages", List.of(List.of("chatId", "messages", "model", "noThink"), List.of("chatId", "messages"))),
            // Added after the table was read (ExternalMcpServersOfAConversation_261120_oo01).
            Map.entry("set_mcp_servers", List.of(List.of("chatId", "servers"), List.of("chatId", "servers"))));

    static final Set<String> DRIVING = Set.of(
            "ask_person", "ask_chat", "ask_chat_urgent", "accept_reply", "cancel_request", "expect_report");

    @Test
    void theCatalogHasExactlyTheToolsTheTableHad() {
        assertEquals(BEFORE.keySet(), ConversationToolCatalog.builtInTools().keySet());
    }

    @Test
    void eachToolKeepsItsArgumentOrderAndItsRequiredArguments() {
        BEFORE.forEach((name, expected) -> {
            ToolCatalog.Spec spec = ConversationToolCatalog.builtIn(name);
            assertNotNull(spec, name);
            assertEquals(expected.get(0), spec.signature(), name + " signature");
            assertEquals(expected.get(1), spec.required(), name + " required");
        });
    }

    @Test
    void askPersonIsListedFirst() {
        assertEquals("ask_person", ConversationToolCatalog.builtInTools().keySet().iterator().next());
    }

    @Test
    void onlyTheSixConversationDrivingToolsAreFlagged() {
        ConversationToolCatalog.builtInTools().forEach((name, spec) ->
                assertEquals(DRIVING.contains(name), spec.drivesConversation(), name));
        assertEquals(DRIVING, Set.copyOf(ConversationToolCatalog.conversationDrivingTools()));
    }

    @Test
    void descriptionsDoNotDependOnTheInvokeForm() {
        ConversationToolCatalog.builtInTools().forEach((name, spec) -> {
            assertFalse(spec.description().contains("<parameter"), name + ": " + spec.description());
            assertFalse(spec.description().contains("<invoke"), name + ": " + spec.description());
            assertFalse(spec.description().contains("\n"), name);
        });
    }

    @Test
    void theInvokeEntryStartsWithTheSignatureAndNamesTheRequiredTags() {
        ConversationToolCatalog.builtInTools().forEach((name, spec) -> {
            String entry = spec.invokeDescription();
            assertTrue(entry.startsWith("- " + name + "(" + String.join(", ", spec.signature()) + "): "), entry);
            assertTrue(entry.endsWith("\n"), name);
            for (String line : entry.split("\n")) assertTrue(line.length() <= 100, name + ": " + line);
            String oneLine = entry.replaceAll("\\s+", " ");
            for (String a : spec.required()) assertTrue(oneLine.contains("\"" + a + "\""), name + " names " + a);
            if (spec.required().isEmpty()) assertFalse(oneLine.contains("Requires"), name);
        });
    }

    @Test
    void requiredSentenceReadsAsEnglish() {
        assertEquals("", ToolCatalog.requiredSentence(List.of()));
        assertEquals(" Requires a \"path\" <parameter> tag.", ToolCatalog.requiredSentence(List.of("path")));
        assertEquals(" Requires \"a\", \"b\" and \"c\" <parameter> tags.",
                ToolCatalog.requiredSentence(List.of("a", "b", "c")));
    }

    @Test
    void theInputSchemaHasTheTypesAndTheRequiredList() {
        JSONObject schema = ConversationToolCatalog.builtIn("complete_messages").inputSchema();
        assertEquals("object", schema.getString("type"));
        JSONObject props = schema.getJSONObject("properties");
        assertEquals(List.of("chatId", "messages", "model", "noThink").stream().sorted().toList(),
                props.keySet().stream().sorted().toList());
        assertEquals("boolean", props.getJSONObject("noThink").getString("type"));
        assertEquals("string", props.getJSONObject("chatId").getString("type"));
        assertEquals(List.of("chatId", "messages"), schema.getJSONArray("required").toList());
        assertEquals("integer",
                ConversationToolCatalog.builtIn("expect_report").inputSchema().getJSONObject("properties")
                        .getJSONObject("minutes").getString("type"));
    }

    @Test
    void aPluginToolWithoutOverrideGetsRequiredStringsFromItsHead() {
        ConversationTool tool = new ConversationTool() {
            @Override public String name() { return "echo"; }
            @Override public String description() {
                return """
                        - echo(text, times): repeat the text
                          the given number of times.
                        """;
            }
            @Override public String execute(String argumentsJson) { return argumentsJson; }
        };
        ToolCatalog.Spec spec = ToolCatalog.ofPlugin(tool);
        assertEquals(List.of(ToolParameter.requiredString("text"), ToolParameter.requiredString("times")),
                spec.parameters());
        assertEquals("repeat the text the given number of times.", spec.description());
        assertFalse(spec.drivesConversation());
    }
}
