package com.scivicslab.chatui.audittrail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.schema.ActionCatalog;

/**
 * Finds actions by their name alone, for the Method Search tab ({@code MethodSearch_261011_oo01}).
 *
 * <p>One match is one action and the class that declares it: the description belongs to that
 * declaration, so the twenty conversations sharing {@code ChatSessionIIAR} make one match listing
 * twenty actor names, an action every actor inherits from one base class is one match, and a class
 * that declares the action again is a match of its own.</p>
 */
public final class ActionSearch {

    private ActionSearch() {}

    /**
     * One action on one class.
     *
     * @param action      the action name, as a workflow writes it under {@code method:}
     * @param className   the simple name of the class whose {@code @Action} it is
     * @param description the first sentence of its Javadoc, or empty
     * @param actors      the names a workflow may write under {@code actor:} to call it, in the order given
     */
    public record Match(String action, String className, String description, List<String> actors) {}

    /**
     * @param query         part of an action name; matched case-insensitively. Blank finds nothing.
     * @param actorNames    every name a workflow may write under {@code actor:}
     * @param classOf       the class whose {@code @Action} methods answer for a name, or null
     * @param descriptionOf the first sentence of an action's Javadoc, or null
     * @return the matches: names equal to the query first, then names starting with it, then the rest;
     *         within each, by action name, then class name
     */
    public static List<Match> search(String query, List<String> actorNames, Function<String, Class<?>> classOf,
                                     BiFunction<Class<?>, String, String> descriptionOf) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.strip().toLowerCase(Locale.ROOT);

        // class -> action -> actor names, in the order the actors were given
        Map<Class<?>, Map<String, List<String>>> found = new LinkedHashMap<>();
        for (String name : actorNames) {
            Class<?> cls = classOf.apply(name);
            if (cls == null) continue;
            for (String action : ActionCatalog.actionNamesOf(cls)) {
                if (!action.toLowerCase(Locale.ROOT).contains(q)) continue;
                found.computeIfAbsent(declaringClassOf(cls, action), c -> new LinkedHashMap<>())
                     .computeIfAbsent(action, a -> new ArrayList<>())
                     .add(name);
            }
        }

        List<Match> matches = new ArrayList<>();
        found.forEach((cls, actions) -> actions.forEach((action, actors) -> {
            String description = descriptionOf.apply(cls, action);
            matches.add(new Match(action, cls.getSimpleName(), description == null ? "" : description, List.copyOf(actors)));
        }));
        matches.sort(Comparator.comparingInt((Match m) -> closeness(m.action(), q))
                .thenComparing(Match::action)
                .thenComparing(Match::className));
        return matches;
    }

    /**
     * The class nearest to {@code cls} that declares {@code action} with {@code @Action}: itself, then
     * its superclasses, then its interfaces — the declaration a call on {@code cls} reaches.
     */
    static Class<?> declaringClassOf(Class<?> cls, String action) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            if (declares(c, action)) return c;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                Class<?> found = declaringInterfaceOf(i, action);
                if (found != null) return found;
            }
        }
        return cls;
    }

    private static Class<?> declaringInterfaceOf(Class<?> iface, String action) {
        if (declares(iface, action)) return iface;
        for (Class<?> parent : iface.getInterfaces()) {
            Class<?> found = declaringInterfaceOf(parent, action);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean declares(Class<?> c, String action) {
        for (Method m : c.getDeclaredMethods()) {
            Action a = m.getAnnotation(Action.class);
            if (a != null && a.value().equals(action)) return true;
        }
        return false;
    }

    /** 0 when the name is the query, 1 when it starts with it, 2 when it only contains it. */
    private static int closeness(String action, String q) {
        String a = action.toLowerCase(Locale.ROOT);
        return a.equals(q) ? 0 : a.startsWith(q) ? 1 : 2;
    }
}
