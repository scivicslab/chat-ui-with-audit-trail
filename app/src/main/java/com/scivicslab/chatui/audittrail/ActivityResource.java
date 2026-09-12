package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers what this instance is working on ({@code ActivitySummary_260905_oo01}).
 *
 * <p>Reads the answer {@link ActivityWatcher} holds and sends it. Works nothing out: whoever asks
 * is drawing a dashboard and gives up after a second, and working an answer out takes one call to
 * a model per project. The watcher renews on its own schedule, so the answer is already there when
 * this is asked.</p>
 */
@Path("/api/activity")
@Produces(MediaType.APPLICATION_JSON)
public class ActivityResource {

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * Returns what this instance is working on.
     *
     * @return {@code {summary, asOf, parts}}; {@code parts} holds one entry per project
     */
    @GET
    public Map<String, Object> activity() {
        ActivityAnswer answer = actorSystem.getActivityWatcher()
                .ask(ActivityWatcher::current).join();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", answer.summary());
        // An answer nothing has worked out yet is dated the epoch inside the watcher, so that the
        // first tick renews it. What goes out is this moment, as it was before the watcher existed.
        out.put("asOf", (answer.isPending() ? java.time.Instant.now() : answer.asOf()).toString());
        out.put("parts", answer.parts());
        return out;
    }
}
