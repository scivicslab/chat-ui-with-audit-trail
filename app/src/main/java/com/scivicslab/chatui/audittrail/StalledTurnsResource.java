package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.StalledTurnRecovery;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The conversations whose turn is stalled, and the way to bring them back ({@code tasks/stalled-turn-recovery.md}).
 * A turn is stalled when its model call has produced nothing at all for {@code chat-ui.stall.threshold-minutes}.
 * Recovering a conversation stops that turn (killing the provider's process if a cancel does not end it), puts the
 * prompt it was working on back at the front of its queue, and lets the queue run again: one call is enough.
 *
 * <ul>
 *   <li>{@code GET /api/stalled-chats}: {@code {thresholdMinutes, autoRecoverMinutes, stalled:[{projectId, chatId,
 *       reason, callStartedAt, silentSeconds, queued}]}}; {@code reason} is {@code silent} (the model call has produced
 *       nothing for the threshold) or {@code usage-limit} (a turn was refused for the usage limit; its request is back
 *       at the front and the queue is held)</li>
 *   <li>{@code POST /api/stalled-chats/recover}: recovers every stalled conversation;
 *       {@code {outcomes:[{projectId, chatId, outcome, how, queued}]}}</li>
 *   <li>{@code POST /api/stalled-chats/{projectId}/{chatId}/recover}: recovers one, stalled or not;
 *       {@code {projectId, chatId, outcome, how, queued}}</li>
 * </ul>
 * {@code outcome} is {@code recovered}, {@code not-running}, {@code not-found} or {@code not-ended}; {@code how} is
 * {@code cancel}, {@code kill}, {@code release} (a queue held for the usage limit) or {@code none}; {@code queued} counts the prompts waiting afterwards, the one put back
 * included. {@code chatId} is {@code 57} or {@code chat-57}.
 */
@Path("/api/stalled-chats")
@Produces(MediaType.APPLICATION_JSON)
public class StalledTurnsResource {

    @Inject
    ChatUiActorSystem actorSystem;

    @GET
    public Map<String, Object> stalled() {
        return actorSystem.getStalledTurns().ask(StalledTurnsResource::listing).join();
    }

    @POST
    @Path("/recover")
    public Map<String, Object> recoverAll() {
        List<StalledTurnRecovery.Outcome> outcomes = actorSystem.getStalledTurns()
                .ask(StalledTurnRecovery::recoverAll).join();
        List<Map<String, Object>> out = new ArrayList<>();
        for (StalledTurnRecovery.Outcome o : outcomes) out.add(outcome(o));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcomes", out);
        return body;
    }

    @POST
    @Path("/{projectId}/{chatId}/recover")
    public Map<String, Object> recoverOne(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        return outcome(actorSystem.getStalledTurns().ask(r -> r.recover(projectId, chatId)).join());
    }

    /** The listing, read on the recovery actor's own thread so it does not run beside a recovery. */
    static Map<String, Object> listing(StalledTurnRecovery recovery) {
        List<Map<String, Object>> stalled = new ArrayList<>();
        for (StalledTurnRecovery.Stalled s : recovery.stalled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", s.projectId());
            m.put("chatId", s.chatId());
            m.put("reason", s.reason());
            m.put("callStartedAt", java.time.Instant.ofEpochMilli(s.callStartedAt()).toString());
            m.put("silentSeconds", s.silentMillis() / 1000);
            m.put("queued", s.queued());
            stalled.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("thresholdMinutes", recovery.thresholdMillis() / 60_000);
        body.put("autoRecoverMinutes", Math.max(0, recovery.autoRecoverMillis() / 60_000));
        body.put("stalled", stalled);
        return body;
    }

    static Map<String, Object> outcome(StalledTurnRecovery.Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", o.projectId());
        m.put("chatId", o.chatId());
        m.put("outcome", o.outcome());
        m.put("how", o.how());
        m.put("queued", o.queued());
        return m;
    }
}
