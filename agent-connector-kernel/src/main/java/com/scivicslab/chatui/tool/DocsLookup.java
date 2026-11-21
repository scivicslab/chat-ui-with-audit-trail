package com.scivicslab.chatui.tool;

import com.scivicslab.chatui.agent.DocSearchTool;
import com.scivicslab.chatui.agent.DocsSource;

/**
 * Chooses where {@code search_docs} and {@code list_references} get their answers, from the {@code node} argument
 * of the call. agent-connector-kernel knows only html-saurus's HTTP API; the app's implementation also knows the
 * html-saurus nodes of the distributed actor system.
 */
@FunctionalInterface
public interface DocsLookup {

    /** html-saurus's HTTP API at {@link DocSearchTool#baseUrl()}, whatever node the call names. */
    DocsLookup HTTP = node -> new DocsSource.Choice(DocsSource.http(DocSearchTool.baseUrl()), null);

    /**
     * @param node the node the call named, or blank
     * @return the source to use, or why there is none
     */
    DocsSource.Choice choose(String node);
}
