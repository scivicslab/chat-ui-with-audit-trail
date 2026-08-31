package com.scivicslab.chatui.core.actor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers what a workflow said it was doing when it created an actor
 * ({@code ActorPurposeFromWorkflowNote_260831_oo01}).
 *
 * <p>A workflow transition carries a {@code note} — the sentence its author wrote to say what that
 * step is for. When a transition creates an actor, that sentence is the closest thing there is to
 * a statement of why the actor exists, and it is written by the person whose intent one wants to
 * read. It is shown in the Actors pane as the actor's tooltip.</p>
 *
 * <p><strong>The note is a claim, not a fact.</strong> Nothing checks that the actor does what the
 * note says, and nothing updates the note when the workflow changes. That is the point: seeing the
 * stated intent next to the actual behaviour is what makes a wrong intent visible.</p>
 *
 * <p>Actors that Java creates carry no note and show none.</p>
 */
public final class ActorNotes {

    private ActorNotes() {}

    /**
     * The note of the transition running on this thread, or {@code null} outside one. Thread-scoped
     * because two conversations run their workflows on their own threads at the same time.
     */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private static final Map<String, String> NOTES = new ConcurrentHashMap<>();

    /**
     * @param note the note of the transition about to run, or {@code null}
     */
    public static void enterTransition(String note) {
        if (note == null || note.isBlank()) CURRENT.remove();
        else CURRENT.set(note.strip());
    }

    /** Ends the window opened by {@link #enterTransition(String)}. */
    public static void leaveTransition() {
        CURRENT.remove();
    }

    /**
     * Attaches the running transition's note to an actor just created by it. Does nothing outside a
     * transition, or if that transition has no note.
     *
     * @param actorName the new actor's registry name
     */
    public static void record(String actorName) {
        String note = CURRENT.get();
        if (note != null && actorName != null) NOTES.put(actorName, note);
    }

    /**
     * @param actorName an actor's registry name
     * @return what the workflow that created it said it was doing, or {@code null}
     */
    public static String noteOf(String actorName) {
        return NOTES.get(actorName);
    }
}
