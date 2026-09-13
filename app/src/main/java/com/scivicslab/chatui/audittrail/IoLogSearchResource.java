package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.iolog.IoLogSearch;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the Log Search tab: one query against every conversation this instance has recorded
 * ({@code CrossConversationLogSearch_260913_oo01}).
 *
 * <p>Separate from {@link SessionsResource}, which answers about the conversation that is open.
 * This one is asked when there is no conversation to ask through — the tab has been removed, or
 * the person does not know which of them said it.</p>
 */
@Path("/api/iolog/search")
public class IoLogSearchResource {

    /** How many hits one search answers with when the caller does not say. */
    private static final int DEFAULT_LIMIT = 100;

    /** The most any one search answers with, however many the caller asks for. */
    private static final int MAX_LIMIT = 500;

    @Inject
    IoLogSearch search;

    /**
     * @param q     the text to look for, matched as a substring of an entry
     * @param limit how many hits to return at most
     * @return the matching entries, newest first
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public IoLogSearch.Result search(@QueryParam("q") String q,
                                     @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
        return search.search(q, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }

    /**
     * @return how many conversations and entries there are to search
     */
    @GET
    @Path("/overview")
    @Produces(MediaType.APPLICATION_JSON)
    public IoLogSearch.Overview overview() {
        return search.overview();
    }
}
