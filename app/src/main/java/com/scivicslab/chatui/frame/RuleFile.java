package com.scivicslab.chatui.frame;

import java.util.ArrayList;
import java.util.List;

/**
 * A rst-on-lisp rule file as text, cut into its top-level forms and the gaps between them, so that
 * one rule can be replaced or added and everything else stays as it was, byte for byte.
 *
 * <p>This reads only as much Lisp as a rule file uses: parentheses, strings with backslash escapes,
 * and {@code ;} comments. A form is {@code (defrule NAME -> ITEM ...)}; an item is a symbol, a
 * keyword such as {@code :instruction}, or a string. Anything else ({@code (in-package :rst)},
 * {@code (defdocument ...)}) is kept as a form that is not a rule.</p>
 */
public final class RuleFile {

    /** One piece of the file: a top-level form, or the text between forms (blank lines, comments). */
    public record Segment(String text, boolean form) {}

    /** One token of a rule's items. */
    public record Token(Kind kind, String raw) {
        public enum Kind { SYMBOL, KEYWORD, STRING }

        /** @return the string's characters with the Lisp escapes undone; the symbol's text otherwise */
        public String value() {
            return kind == Kind.STRING ? unescape(raw.substring(1, raw.length() - 1)) : raw;
        }
    }

    /**
     * A {@code defrule} form.
     *
     * @param name  the rule's name
     * @param items everything after {@code ->}, in order
     */
    public record Rule(String name, List<Token> items) {

        /** @return the string that follows {@code key}, or {@code null} when the rule has no such attribute */
        public String attr(String key) {
            for (int i = 0; i + 1 < items.size(); i++) {
                Token t = items.get(i);
                if (t.kind() == Token.Kind.KEYWORD && t.raw().equals(key)
                        && items.get(i + 1).kind() == Token.Kind.STRING) {
                    return items.get(i + 1).value();
                }
            }
            return null;
        }

        /** @return whether the rule's body is one string and nothing else, i.e. it has been written */
        public boolean isSentence() {
            return items.size() == 1 && items.get(0).kind() == Token.Kind.STRING;
        }
    }

    private final List<Segment> segments;

    private RuleFile(List<Segment> segments) {
        this.segments = segments;
    }

    /** @param text the whole rule file */
    public static RuleFile parse(String text) {
        List<Segment> out = new ArrayList<>();
        int i = 0;
        int gapStart = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ';') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
            } else if (c == '(') {
                if (i > gapStart) out.add(new Segment(text.substring(gapStart, i), false));
                int end = endOfForm(text, i);
                out.add(new Segment(text.substring(i, end), true));
                i = end;
                gapStart = end;
            } else {
                i++;
            }
        }
        if (gapStart < text.length()) out.add(new Segment(text.substring(gapStart), false));
        return new RuleFile(out);
    }

    /** @return the index just past the form that starts at {@code start} */
    private static int endOfForm(String text, int start) {
        int depth = 0;
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                i++;
                while (i < text.length() && text.charAt(i) != '"') i += text.charAt(i) == '\\' ? 2 : 1;
            } else if (c == ';') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("unbalanced parentheses in the form that starts at offset " + start);
    }

    /** @return the pieces in file order */
    public List<Segment> segments() {
        return segments;
    }

    /** @return the whole file, as it was read unless something was replaced or added */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) sb.append(s.text());
        return sb.toString();
    }

    /** @return the {@code defrule} forms, in file order; the other forms are not rules */
    public List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (Segment s : segments) {
            Rule r = s.form() ? ruleOf(s.text()) : null;
            if (r != null) rules.add(r);
        }
        return rules;
    }

    /** @return the rule of that name, or {@code null} */
    public Rule rule(String name) {
        for (Rule r : rules()) if (r.name().equals(name)) return r;
        return null;
    }

    /**
     * Replaces the rule of that name with new text.
     *
     * @throws IllegalArgumentException when there is no such rule
     */
    public RuleFile replace(String name, String newForm) {
        List<Segment> out = new ArrayList<>();
        boolean done = false;
        for (Segment s : segments) {
            Rule r = s.form() ? ruleOf(s.text()) : null;
            if (!done && r != null && r.name().equals(name)) {
                out.add(new Segment(newForm, true));
                done = true;
            } else {
                out.add(s);
            }
        }
        if (!done) throw new IllegalArgumentException("no rule named " + name);
        return new RuleFile(out);
    }

    /**
     * Adds forms, one per line, right after the rule of that name.
     *
     * @throws IllegalArgumentException when there is no such rule
     */
    public RuleFile insertAfter(String name, List<String> forms) {
        List<Segment> out = new ArrayList<>();
        boolean done = false;
        for (Segment s : segments) {
            out.add(s);
            Rule r = s.form() ? ruleOf(s.text()) : null;
            if (!done && r != null && r.name().equals(name)) {
                for (String f : forms) {
                    out.add(new Segment("\n", false));
                    out.add(new Segment(f, true));
                }
                done = true;
            }
        }
        if (!done) throw new IllegalArgumentException("no rule named " + name);
        return new RuleFile(out);
    }

    /** @return the rule a form text is, or {@code null} when it is not {@code (defrule NAME -> ...)} */
    static Rule ruleOf(String form) {
        List<Token> tokens = tokens(form.substring(1, form.length() - 1));
        if (tokens.size() < 3 || tokens.get(0).kind() != Token.Kind.SYMBOL
                || !tokens.get(0).raw().equalsIgnoreCase("defrule")
                || tokens.get(1).kind() != Token.Kind.SYMBOL
                || !tokens.get(2).raw().equals("->")) {
            return null;
        }
        return new Rule(tokens.get(1).raw(), List.copyOf(tokens.subList(3, tokens.size())));
    }

    /** @return the tokens of a form's inside, comments dropped */
    static List<Token> tokens(String inside) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < inside.length()) {
            char c = inside.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == ';') {
                while (i < inside.length() && inside.charAt(i) != '\n') i++;
            } else if (c == '"') {
                int j = i + 1;
                while (j < inside.length() && inside.charAt(j) != '"') j += inside.charAt(j) == '\\' ? 2 : 1;
                out.add(new Token(Token.Kind.STRING, inside.substring(i, Math.min(j + 1, inside.length()))));
                i = j + 1;
            } else {
                int j = i;
                while (j < inside.length() && !Character.isWhitespace(inside.charAt(j))
                        && inside.charAt(j) != '"' && inside.charAt(j) != ';') j++;
                String word = inside.substring(i, j);
                out.add(new Token(word.startsWith(":") ? Token.Kind.KEYWORD : Token.Kind.SYMBOL, word));
                i = j;
            }
        }
        return out;
    }

    /** @return {@code s} written as the inside of a Lisp string */
    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** @return {@code s}, the inside of a Lisp string, with its escapes undone */
    public static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
