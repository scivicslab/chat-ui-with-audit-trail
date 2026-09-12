package com.scivicslab.chatui.webtools;

import com.scivicslab.chatui.agent.FetchTool;
import com.scivicslab.chatui.agent.WebSearchTool;
import com.scivicslab.chatui.plugin.ChatUiPlugin;
import com.scivicslab.chatui.plugin.ConversationTool;

import org.json.JSONObject;

import java.util.List;

/**
 * The plugin that lets a conversation reach the web ({@code ProviderAndToolPlugins_260912_oo01}):
 * {@code web_search} (DuckDuckGo, then each result's page) and {@code fetch} (one URL). An
 * instance started without this jar has no tool that leaves the local network; one started with it
 * records every call and its whole result in the I/O log.
 */
public class WebToolsPlugin implements ChatUiPlugin {

    @Override public String id() { return "web-tools"; }

    @Override
    public List<ConversationTool> tools() {
        return List.of(new WebSearch(), new Fetch());
    }

    static String input(String argumentsJson, String field) {
        try {
            return new JSONObject(argumentsJson == null ? "{}" : argumentsJson).optString(field, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code web_search(query)}: search, fetch the top results' pages, trimmed per result. */
    static final class WebSearch implements ConversationTool {
        @Override public String name() { return "web_search"; }

        @Override
        public String description() {
            return """
                    - web_search(query): search the web and fetch the top results' page content.
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            return WebSearchTool.searchAndFetch(input(argumentsJson, "query"));
        }

        @Override public boolean returnsPages() { return true; }

        /** Every page whole: the conversation's page-summarising transition fits them itself. */
        @Override
        public String executeUntrimmed(String argumentsJson) {
            return WebSearchTool.searchAndFetch(input(argumentsJson, "query"), WebSearchTool.FETCH_TOP_N, page -> page);
        }
    }

    /** {@code fetch(url)}: one page's readable text, cut at a fixed length and saying so. */
    static final class Fetch implements ConversationTool {
        @Override public String name() { return "fetch"; }

        @Override
        public String description() {
            return """
                    - fetch(url): fetch one specific URL you already have and return its readable text.
                      Stops at 5,000 characters and says "[truncated N chars total]" with the page's real
                      length, so you can tell a whole page from a cut one. This is how you read a page
                      web_search only summarised for you.
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            return FetchTool.fetch(input(argumentsJson, "url"));
        }
    }
}
