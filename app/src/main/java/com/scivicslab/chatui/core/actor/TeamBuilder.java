package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds a team of ConversationSquad actors in one go: a number of middle managers under the liaison, each with its
 * workers, every one made on the provider and model its role says ({@code TeamBuiltByProjectWorkflow_261108_oo01}).
 *
 * <p>Three steps, each a method. {@link #check} turns the inputs into a {@link Plan} and makes nothing. {@link #build}
 * makes what the plan says, and either all of it or none: when any step fails it deletes what it made, subordinates
 * first, and fails with the reason. {@link #report} writes the tree that was built and puts one message with it in the
 * liaison's queue. All it does to the ConversationSquad actors goes through a {@link TeamDesk}.</p>
 *
 * <p>A plain object: no state of its own, so the steps may be called on the same instance or on any other.</p>
 */
public final class TeamBuilder {

    /** The longest {@code work}: names are at most 80 characters, and the role and number follow it. */
    static final int MAX_WORK_LENGTH = 40;
    /** The longest name {@code set_chat_name} takes. */
    static final int MAX_NAME_LENGTH = 80;

    /**
     * What a run was asked for; all text, as a project workflow's inputs are.
     *
     * @param owner               the job's actor name, e.g. {@code project1/job-02}; the project is before the {@code /}
     * @param managers            how many middle managers, at least 1
     * @param workersPerManager   workers under each manager
     * @param repeatersPerManager repeaters (workers on a local LLM) under each manager
     * @param work                a few words saying what the team is for; goes into every name
     * @param managerProvider     overrides the manager role's provider for this run; blank keeps the table's
     * @param managerModel        overrides the manager role's model for this run; blank keeps the table's
     * @param workerProvider      as above, for the worker role
     * @param workerModel         as above
     * @param repeaterProvider    as above, for the repeater role
     * @param repeaterModel       as above
     */
    public record Inputs(String owner, String managers, String workersPerManager, String repeatersPerManager,
                         String work, String managerProvider, String managerModel, String workerProvider,
                         String workerModel, String repeaterProvider, String repeaterModel) {}

    /**
     * One ConversationSquad actor the plan makes.
     *
     * @param key         the plan's name for it: {@code m1}, {@code m1w2}, {@code m1r1}
     * @param role        {@code manager}, {@code worker} or {@code repeater}
     * @param name        the name it is given
     * @param superiorKey the key of its superior, or {@code null} for the liaison
     * @param settings    its provider, model and limits
     */
    public record Member(String key, String role, String name, String superiorKey, TeamRoles.Role settings) {}

    /**
     * What {@link #check} decided.
     *
     * @param projectId the project the team is made in
     * @param liaison   the bare id of the liaison, the superior of every manager
     * @param members   the ConversationSquad actors to make, each manager before its workers
     */
    public record Plan(String projectId, String liaison, List<Member> members) {}

    /**
     * One ConversationSquad actor that was made.
     *
     * @param member     what the plan said
     * @param chatId     the bare id it got
     * @param superiorId the bare id of its superior
     */
    public record Built(Member member, String chatId, String superiorId) {}

    private final TeamDesk desk;

    /** @param desk the project's ConversationSquad actors, as far as a team needs them */
    public TeamBuilder(TeamDesk desk) {
        this.desk = desk;
    }

    /**
     * Turns the inputs into a plan, and makes nothing.
     *
     * @throws IllegalArgumentException when the inputs cannot make a team: a count that is not a whole number, more
     *                                  managers than the liaison has room for, more subordinates than one superior may
     *                                  have, a {@code work} or name that is too long, a provider this instance does
     *                                  not offer, or a role table that is not one
     * @throws IllegalStateException    when no desk is registered
     */
    public Plan check(Inputs in) {
        String projectId = projectOf(in.owner());
        int managers = whole(in.managers(), "managers", 1);
        int workers = whole(in.workersPerManager(), "workersPerManager", 0);
        int repeaters = whole(in.repeatersPerManager(), "repeatersPerManager", 0);
        String work = in.work() == null ? "" : in.work().strip();
        if (work.length() > MAX_WORK_LENGTH) {
            throw new IllegalArgumentException("work is at most " + MAX_WORK_LENGTH + " characters; this one has " + work.length());
        }
        String label = work.isEmpty() ? jobOf(in.owner()) : work;

        List<TeamDesk.Provider> providers = desk.providers();
        TeamRoles roles = TeamRoles.of(desk.rolesText(projectId))
                .overriding("manager", in.managerProvider(), in.managerModel())
                .overriding("worker", in.workerProvider(), in.workerModel())
                .overriding("repeater", in.repeaterProvider(), in.repeaterModel());
        requireProvider(providers, "manager", roles);
        if (workers > 0) requireProvider(providers, "worker", roles);
        if (repeaters > 0) requireProvider(providers, "repeater", roles);

        int limit = desk.subordinateLimit(projectId);
        if (workers + repeaters > limit) {
            throw new IllegalArgumentException("a manager may have at most " + limit + " subordinates; this plan gives each one "
                    + (workers + repeaters));
        }
        String liaison = desk.liaisonOf(projectId);
        int room = desk.roomLeft(projectId, liaison);
        if (managers > room) {
            throw new IllegalArgumentException("the liaison chat-" + liaison + " has room for " + room
                    + " more subordinates; this plan asks for " + managers + " managers");
        }

        List<Member> members = new ArrayList<>();
        for (int m = 1; m <= managers; m++) {
            String managerKey = "m" + m;
            members.add(new Member(managerKey, "manager", name(label, "manager " + m), null, roles.role("manager")));
            for (int w = 1; w <= workers; w++) {
                members.add(new Member(managerKey + "w" + w, "worker", name(label, "worker " + m + "-" + w), managerKey,
                        roles.role("worker")));
            }
            for (int r = 1; r <= repeaters; r++) {
                members.add(new Member(managerKey + "r" + r, "repeater", name(label, "repeater " + m + "-" + r), managerKey,
                        roles.role("repeater")));
            }
        }
        return new Plan(projectId, liaison, List.copyOf(members));
    }

    /**
     * Makes what the plan says: each ConversationSquad actor is made, put on its provider and model, named, put under
     * its superior and given its timekeeper limits, before the next one is made.
     *
     * @return what was made, in the plan's order
     * @throws IllegalStateException when any step fails, after everything made so far was deleted, subordinates first;
     *                               the message says what failed
     */
    public List<Built> build(Plan plan) {
        String projectId = plan.projectId();
        List<TeamDesk.Provider> providers = desk.providers();
        Map<String, String> idOfKey = new HashMap<>();
        List<String> made = new ArrayList<>();
        List<Built> built = new ArrayList<>();
        try {
            for (Member member : plan.members()) {
                String chatId = desk.createChat(projectId);
                made.add(chatId);
                TeamRoles.Role settings = member.settings();
                TeamDesk.Provider provider = providers.stream().filter(p -> p.value().equals(settings.provider()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("unknown provider '" + settings.provider() + "'"));
                desk.setProvider(projectId, chatId, provider);
                if (!settings.model().isBlank()) {
                    List<String> available = desk.modelNames(projectId, chatId);
                    if (!available.isEmpty() && !available.contains(settings.model())) {
                        throw new IllegalStateException("'" + settings.model() + "' is not one of the models of "
                                + settings.provider() + "; available: " + String.join(", ", available));
                    }
                    desk.setModel(projectId, chatId, settings.model());
                }
                String named = desk.setChatName(projectId, chatId, member.name());
                if (named != null) throw new IllegalStateException("could not name chat-" + chatId + ": " + named);
                String superiorId = member.superiorKey() == null ? plan.liaison() : idOfKey.get(member.superiorKey());
                String placed = desk.setSuperior(projectId, chatId, superiorId);
                if (placed != null) {
                    throw new IllegalStateException("could not put chat-" + chatId + " under chat-" + superiorId + ": " + placed);
                }
                desk.setTimekeeperLimits(projectId, chatId, settings.urgentWaitMinutes(), settings.childReplyWarnMinutes());
                idOfKey.put(member.key(), chatId);
                built.add(new Built(member, chatId, superiorId));
            }
        } catch (RuntimeException e) {
            List<String> kept = new ArrayList<>();
            for (int i = made.size() - 1; i >= 0; i--) {
                boolean gone;
                try {
                    gone = desk.deleteChat(projectId, made.get(i));
                } catch (RuntimeException deleteFailure) {
                    gone = false;
                }
                if (!gone) kept.add("chat-" + made.get(i));
            }
            throw new IllegalStateException(e.getMessage() + (made.isEmpty() ? "" : "; the team was not built"
                    + (kept.isEmpty() ? " and everything made was deleted" : ", but these could not be deleted: " + String.join(", ", kept))), e);
        }
        return List.copyOf(built);
    }

    /**
     * Writes the tree that was built, and puts the same tree, as one message, in the liaison's queue. A message that
     * cannot be queued does not undo the team: the tree says so.
     *
     * @param owner the job's actor name, which is where the message comes from
     * @return the tree, one line per ConversationSquad actor (id, name, role, provider and model, superior)
     */
    public String report(String owner, Plan plan, List<Built> built) {
        String tree = tree(plan.projectId(), built);
        String notice = "[team built by job " + owner + "]\n"
                + "This is a notice, not a request: no reply is expected. The job built this team under you:\n" + tree;
        String refusal;
        try {
            refusal = desk.notifyConversation(plan.projectId(), plan.liaison(), "job:" + owner, notice);
        } catch (RuntimeException e) {
            refusal = e.getMessage();
        }
        return refusal == null ? tree : tree + "\nthe liaison was not notified: " + refusal;
    }

    private static String tree(String projectId, List<Built> built) {
        return built.stream().map(b -> (b.member().superiorKey() == null ? "" : "  ")
                + projectId + "/chat-" + b.chatId() + " \"" + b.member().name() + "\" - " + b.member().role() + ", "
                + b.member().settings().provider() + " "
                + (b.member().settings().model().isBlank() ? "(default model)" : b.member().settings().model())
                + ", superior chat-" + b.superiorId()).collect(Collectors.joining("\n"));
    }

    private static String name(String label, String roleAndNumber) {
        String name = label + " / " + roleAndNumber;
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("the name '" + name + "' is longer than " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    private static void requireProvider(List<TeamDesk.Provider> providers, String role, TeamRoles roles) {
        String wanted = roles.role(role).provider();
        if (providers.stream().noneMatch(p -> p.value().equals(wanted))) {
            throw new IllegalArgumentException("unknown provider '" + wanted + "' for the " + role + " role; choose one of: "
                    + providers.stream().map(TeamDesk.Provider::value).collect(Collectors.joining(", ")));
        }
    }

    private static int whole(String text, String what, int least) {
        int n;
        try {
            n = Integer.parseInt(text == null ? "" : text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number of at least " + least + ": '" + text + "'");
        }
        if (n < least) throw new IllegalArgumentException(what + " must be a whole number of at least " + least + ": " + n);
        return n;
    }

    private static String projectOf(String owner) {
        int slash = owner == null ? -1 : owner.indexOf('/');
        if (slash <= 0) throw new IllegalArgumentException("owner must be the job's actor name such as project1/job-02: '" + owner + "'");
        return owner.substring(0, slash);
    }

    private static String jobOf(String owner) {
        return owner.substring(owner.indexOf('/') + 1);
    }
}
