package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;
import com.scivicslab.pojoactor.core.ActorRef;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Works out what this instance is doing ({@code ActivitySummary_260905_oo01}).
 *
 * <p>What a conversation is about is not written down anywhere. Twenty exchanges have no title, and
 * the project ids that separate them — {@code project1}, {@code project2} — are names for telling
 * them apart, not statements about what they hold. So unlike html-saurus, which has titles its
 * authors wrote, this has to be read and said, and a model reads it.</p>
 *
 * <p>The table gets {@code project1}'s subject and a count of the rest; the Detail screen gets one
 * line per project. {@code project1} is the one that always exists
 * ({@code ChatUiActorSystem.DEFAULT_PROJECT_ID}).</p>
 *
 * <p>Holds no state of its own. {@link ActivityWatcher} holds the answer this produces and decides
 * when to produce another; this class only reads the conversations and asks a model to name their
 * subjects. Separate from the watcher because working one out takes one call to a model per
 * project and must not run on the watcher's mailbox.</p>
 */
@ApplicationScoped
public class ActivityWork {

    private static final Logger LOG = Logger.getLogger(ActivityWork.class.getName());

    @Inject
    ChatUiActorSystem actorSystem;

    @Inject
    IoLogStore ioLogStore;

    @Inject
    IoLogView ioLogView;

    /** How many of a conversation's most recent turns are read to work out what it is doing.
     *
     * <p>The answer names both the thing being worked on and what is being done to it, and those
     * two are rarely in the same turn: the name is usually settled early and the work is in the
     * last few exchanges. Wide enough to hold both, and not so wide that a subject the
     * conversation has since dropped gets pulled in.</p>
     */
    static final int TURNS_READ = 30;

    /** How much of one turn is passed on. A subject does not need whole answers. */
    static final int CHARS_PER_TURN = 400;

    /**
     * Reads every project's conversation and asks the model what each one is about.
     *
     * <p>Calls a model once per project, so it takes as long as those calls do. Callers run it off
     * any mailbox.</p>
     *
     * @return the answer to hold until the next one is worked out
     */
    public ActivityAnswer compute() {
        List<String> projectIds = new ArrayList<>(
                actorSystem == null ? List.of() : actorSystem.getProjectIds());
        // In the order a reader counts them. The actor system hands them over in creation order,
        // which after a restore is the order the log happened to be read in — project2, project1,
        // project3 on the instance this was first tried on.
        projectIds.sort(java.util.Comparator.comparingInt(ActivityWork::numberIn)
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
            summary = recorded == 0 ? "No conversation recorded yet."
                                    : "There are " + recorded
                                      + " conversations, but they could not be summarised.";
        } else if (parts.size() == 1) {
            summary = first;
        } else {
            summary = first + " and " + (parts.size() - 1) + " more projects.";
        }
        return new ActivityAnswer(summary, Instant.now(), List.copyOf(parts), !parts.isEmpty());
    }

    /**
     * The number a project id ends in, for ordering.
     *
     * @param projectId e.g. {@code project10}
     * @return the trailing number, or {@link Integer#MAX_VALUE} for an id that ends in no digit,
     *         which then sorts last and among its own kind by name
     */
    static int numberIn(String projectId) {
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
    static String clip(String s) {
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
                Say in one English sentence what is being done in this conversation right now.

                Write it as a piece of work being done to a named thing, for example
                "Reworking the UI design of quarkus-AI-workspace" or
                "Refactoring the parallel execution in Turing-workflow".

                Constraints:
                - Start with the work itself: fixing, refactoring, designing, measuring, writing,
                  deploying, investigating, and so on.
                - Name the program, project or document the work is being done to, using the name
                  the conversation calls it by, for example quarkus-AI-workspace or doc_SCIVICS002.
                - Do not name a field of work or an industry. "software development", "the AI
                  workspace domain", "knowledge management" and the like tell a reader nothing that
                  separates this conversation from any other, and must not appear.
                - Take the work from the most recent exchanges. A subject the conversation has
                  already finished with is not what is being done now.
                - One sentence, at most 15 words. No preamble, no quotation marks.
                - Do not write hostnames, IP addresses, file paths, credentials, or commands.
                - Do not copy the conversation text verbatim.

                Conversation:
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
