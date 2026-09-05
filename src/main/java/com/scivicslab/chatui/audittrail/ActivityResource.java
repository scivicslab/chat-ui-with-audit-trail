package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;
import com.scivicslab.pojoactor.core.ActorRef;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Answers what this instance is working on ({@code ActivitySummary_260905_oo01}).
 *
 * <p>What a conversation is about is not written down anywhere. Twenty exchanges have no title, and
 * the project ids that separate them — {@code project1}, {@code project2} — are names for telling
 * them apart, not statements about what they hold. So unlike html-saurus, which has titles its
 * authors wrote, this has to be read and said, and a model reads it.</p>
 *
 * <p>The table gets {@code project1}'s subject and a count of the rest; the Detail screen gets one
 * line per project. {@code project1} is the one that always exists
 * ({@code ChatUiActorSystem.DEFAULT_PROJECT_ID}).</p>
 */
@Path("/api/activity")
@Produces(MediaType.APPLICATION_JSON)
public class ActivityResource {

    private static final Logger LOG = Logger.getLogger(ActivityResource.class.getName());

    @Inject
    ChatUiActorSystem actorSystem;

    @Inject
    IoLogStore ioLogStore;

    @Inject
    IoLogView ioLogView;

    /**
     * How long an answer stands before it is worked out again.
     *
     * <p>Every answer costs one call to the model per project, and this is drawn on a screen that
     * lists every running tool. What it says — which piece of work a conversation is on — does not
     * turn over in minutes.</p>
     */
    private static final Duration MAX_AGE = Duration.ofMinutes(30);

    /**
     * How long an answer that no model produced stands.
     *
     * <p>Much shorter, because these are the two answers that are about to stop being true. An
     * instance is asked as soon as it is READY, before anyone has said anything to it, and the
     * answer worked out then — "no conversation recorded yet" — would otherwise be repeated for
     * half an hour after the work began. The same goes for a failure: the model being unreachable
     * now says nothing about the next half hour. Neither costs a model call to work out again.</p>
     */
    private static final Duration RETRY_AGE = Duration.ofMinutes(1);

    /** How many of a conversation's most recent turns are read to work out its subject. */
    private static final int TURNS_READ = 12;

    /** How much of one turn is passed on. A subject does not need whole answers. */
    private static final int CHARS_PER_TURN = 400;

    private volatile Answer cached;

    /**
     * One worked-out answer and the moment it was worked out.
     *
     * @param fromModel whether a model produced it, which decides how long it stands
     */
    private record Answer(String summary, Instant asOf, List<Map<String, String>> parts,
                          boolean fromModel) {}

    /**
     * Returns what this instance is working on.
     *
     * @return {@code {summary, asOf, parts}}; {@code parts} holds one entry per project
     */
    @GET
    public Map<String, Object> activity() {
        Answer answer = cached;
        Duration age = answer == null ? null : Duration.between(answer.asOf(), Instant.now());
        if (answer == null || age.compareTo(answer.fromModel() ? MAX_AGE : RETRY_AGE) > 0) {
            answer = work();
            cached = answer;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", answer.summary());
        out.put("asOf", answer.asOf().toString());
        out.put("parts", answer.parts());
        return out;
    }

    /** Reads every project's conversation and asks the model what each one is about. */
    private Answer work() {
        List<String> projectIds = new ArrayList<>(
                actorSystem == null ? List.of() : actorSystem.getProjectIds());
        // In the order a reader counts them. The actor system hands them over in creation order,
        // which after a restore is the order the log happened to be read in — project2, project1,
        // project3 on the instance this was first tried on.
        projectIds.sort(java.util.Comparator.comparingInt(ActivityResource::numberIn)
                .thenComparing(java.util.Comparator.naturalOrder()));
        List<Map<String, String>> parts = new ArrayList<>();
        String first = "";
        int recorded = 0;

        for (String projectId : projectIds) {
            if (hasConversation(projectId)) recorded++;
            String subject = subjectOf(projectId);
            if (subject.isBlank()) continue;
            Map<String, String> part = new LinkedHashMap<>();
            part.put("name", projectId);
            part.put("summary", subject);
            parts.add(part);
            if (ChatUiActorSystem.DEFAULT_PROJECT_ID.equals(projectId)) first = subject;
        }
        if (first.isBlank() && !parts.isEmpty()) first = parts.get(0).get("summary");

        String summary;
        if (parts.isEmpty()) {
            // Two different answers, kept apart: a conversation nobody has had yet, and one this
            // could not read back. Reporting the second as the first sends whoever reads it
            // looking in the wrong place.
            summary = recorded == 0 ? "まだ会話が記録されていない。"
                                    : "会話は" + recorded + "件あるが、要約できなかった。";
        } else if (parts.size() == 1) {
            summary = first;
        } else {
            summary = first + "ほかに" + (parts.size() - 1) + "プロジェクト。";
        }
        return new Answer(summary, Instant.now(), List.copyOf(parts), !parts.isEmpty());
    }

    /**
     * The number a project id ends in, for ordering.
     *
     * @param projectId e.g. {@code project10}
     * @return the trailing number, or {@link Integer#MAX_VALUE} for an id that ends in no digit,
     *         which then sorts last and among its own kind by name
     */
    private static int numberIn(String projectId) {
        int i = projectId.length();
        while (i > 0 && Character.isDigit(projectId.charAt(i - 1))) i--;
        if (i == projectId.length()) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(projectId.substring(i));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** @return whether this project's first conversation has anything recorded at all */
    private boolean hasConversation(String projectId) {
        String tabId = ChatUiActorSystem.chatActorName(projectId, "01");
        return ioLogStore != null && ioLogStore.findResumableSession(tabId) >= 0;
    }

    /**
     * What one project's first conversation is about, in one line.
     *
     * @param projectId the project
     * @return the subject, or {@code ""} when the project has no recorded conversation or the model
     *         could not be reached
     */
    private String subjectOf(String projectId) {
        String tabId = ChatUiActorSystem.chatActorName(projectId, "01");
        long sessionId = ioLogStore == null ? -1 : ioLogStore.findResumableSession(tabId);
        if (sessionId < 0) return "";

        List<IoLogView.Turn> turns;
        try {
            turns = ioLogView.conversation(sessionId, TURNS_READ);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not read the conversation of " + tabId, e);
            return "";
        }
        if (turns.isEmpty()) return "";

        StringBuilder material = new StringBuilder();
        for (IoLogView.Turn t : turns) {
            material.append("Q: ").append(clip(t.question())).append("\n")
                    .append("A: ").append(clip(t.answer())).append("\n\n");
        }
        String reply = ask(projectId, material.toString());
        return reply == null ? "" : reply.strip();
    }

    /** Keeps a turn short: a subject is drawn from what was asked, not from the whole answer. */
    private static String clip(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= CHARS_PER_TURN ? one : one.substring(0, CHARS_PER_TURN) + "…";
    }

    /**
     * Asks the model for the subject of one conversation.
     *
     * <p>Through {@code completeOutsideConversation}, which sends one message and holds nothing:
     * this is not part of the conversation being described, and must not appear in it.</p>
     *
     * <p>The instruction says what must not come back as well as what must. This answer is drawn on
     * the portal's Instances screen, which everyone who can open the portal sees — not only whoever
     * may open this conversation.</p>
     */
    private String ask(String projectId, String material) {
        ActorRef<LlmProvider> ref = actorSystem.getProviderRef(projectId, "01");
        if (ref == null) return null;
        String prompt = """
                次の会話が何についてのものかを、日本語1文で述べてください。

                制約:
                - 何の作業をしているかを述べる。話題の分野ではなく、その会話で進めている作業。
                - 1文。40字以内。前置きも引用符も付けない。
                - 計算機名・IPアドレス・ファイルパス・資格情報・コマンドは書かない。
                - 会話の本文をそのまま写さない。

                会話:
                """ + material;
        try {
            return ref.ask(p -> p instanceof OpenAiCompatProvider o
                    ? o.completeOutsideConversation(prompt) : null).get();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not ask for the subject of " + projectId, e);
            return null;
        }
    }
}
