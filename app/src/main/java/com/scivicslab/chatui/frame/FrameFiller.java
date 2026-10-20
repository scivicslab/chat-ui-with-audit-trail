package com.scivicslab.chatui.frame;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of filling a frame that need no model and no workflow: finding the next blank in a
 * rule file, expanding a {@code :frame} blank into one copy of the sub-frame per item, writing a
 * sentence into a blank, and building what the writer is asked for each blank
 * ({@code WorkflowFromFrame_261020_oo01}).
 *
 * <p>A <em>blank</em> is a rule that carries an {@code :instruction}. A <em>frame blank</em> also
 * carries {@code :frame "file.lisp"}: the writer lists the items to explain, and each item gets a
 * copy of that file's rules, named {@code <blank>/<n>} for the file's first rule and
 * {@code <blank>/<n>/<suffix>} for the rules {@code <root>/<suffix>} under it. A <em>sentence
 * blank</em> is filled with one piece of text.</p>
 *
 * <p>Every method takes and returns text; none keeps state, so the workflow that calls them
 * (through the plan runner) holds the rule file in its own state.</p>
 */
public final class FrameFiller {

    /**
     * The next thing to write.
     *
     * @param name        the rule's name
     * @param instruction what to write, as the frame says it
     * @param sample      a model sentence from another document, or {@code ""}
     * @param frame       the sub-frame's file name for a frame blank, else {@code ""}
     */
    public record Blank(String name, String instruction, String sample, String frame) {
        /** @return whether this blank is a list of items, each explained with the sub-frame */
        public boolean isFrame() {
            return !frame.isBlank();
        }
    }

    /** A rule named {@code <parent>/<n>/<suffix>}: a leaf of the n-th copy of a sub-frame. */
    private static final Pattern LEAF = Pattern.compile("^(.+)/(\\d+)/([^/]+)$");

    /** @return the first blank in the file, or {@code null} when every rule has been written */
    public Blank next(String fileText) {
        for (RuleFile.Rule r : RuleFile.parse(fileText).rules()) {
            String instruction = r.attr(":instruction");
            if (instruction == null) continue;
            String sample = r.attr(":sample");
            String frame = r.attr(":frame");
            return new Blank(r.name(), instruction, sample == null ? "" : sample, frame == null ? "" : frame);
        }
        return null;
    }

    /**
     * Replaces a frame blank with the list of its copies and adds the copies after it.
     *
     * @param fileText  the rule file as it stands
     * @param blankName the frame blank
     * @param frameText the sub-frame's file; its first rule is the root of one copy
     * @param names     the items; there is one copy per item, numbered from 1
     * @return the rule file with the copies in it
     * @throws IllegalArgumentException when the blank is not a frame blank, the sub-frame has no rule,
     *                                  or there are no items
     */
    public String expand(String fileText, String blankName, String frameText, List<String> names) {
        RuleFile file = RuleFile.parse(fileText);
        RuleFile.Rule blank = file.rule(blankName);
        if (blank == null || blank.attr(":frame") == null) {
            throw new IllegalArgumentException(blankName + " is not a blank with a :frame");
        }
        if (names.isEmpty()) throw new IllegalArgumentException("no items to expand " + blankName + " with");
        List<RuleFile.Rule> frameRules = RuleFile.parse(frameText).rules();
        if (frameRules.isEmpty()) throw new IllegalArgumentException("the sub-frame has no rule");
        String root = frameRules.get(0).name();

        List<String> copies = new ArrayList<>();
        StringBuilder children = new StringBuilder();
        for (int n = 1; n <= names.size(); n++) {
            String copyRoot = blankName + "/" + n;
            if (children.length() > 0) children.append(' ');
            children.append(copyRoot);
            for (RuleFile.Rule r : frameRules) {
                copies.add(form(rename(r.name(), root, copyRoot), renameSymbols(r.items(), root, copyRoot)));
            }
        }
        String list = form(blankName, children + restOf(blank.items()));
        return file.replace(blankName, list).insertAfter(blankName, copies).text();
    }

    /**
     * Writes a sentence into a blank, in place of its {@code :instruction}, {@code :sample} and {@code :frame}.
     *
     * @throws IllegalArgumentException when there is no such rule
     */
    public String fill(String fileText, String blankName, String sentence) {
        RuleFile file = RuleFile.parse(fileText);
        RuleFile.Rule rule = file.rule(blankName);
        if (rule == null) throw new IllegalArgumentException("no rule named " + blankName);
        String body = "\"" + RuleFile.escape(oneLine(sentence)) + "\"" + restOf(rule.items());
        return file.replace(blankName, form(blankName, body)).text();
    }

    /**
     * What the writer is asked for a frame blank: the items to explain.
     *
     * @param maxItems how many items at most
     */
    public String listPrompt(Blank blank, String subject, int maxItems, String language) {
        return "Below is the material that will be explained to a reader.\n\n"
                + fenced(subject)
                + "\n\nThe document's author describes this part as: " + blank.instruction() + "\n\n"
                + "List the items a reader needs explained, one per line, each only the item's name, at most "
                + maxItems + " items. Write the names in " + language
                + ". No numbering, bullet marks, quotation marks or full stops. Write nothing else.";
    }

    /**
     * What the writer is asked for a sentence blank.
     *
     * <p>A leaf of a sub-frame copy ({@code <blank>/<n>/<suffix>}) is asked about the n-th item and is
     * shown what the same copy already says; any other blank is asked about the material as a whole.</p>
     *
     * @param fileText the rule file as it stands
     * @param names    the items a frame blank was expanded with, in order
     */
    public String sentencePrompt(String fileText, Blank blank, String subject, List<String> names, String language) {
        StringBuilder sb = new StringBuilder();
        Matcher leaf = LEAF.matcher(blank.name());
        if (leaf.matches()) {
            int n = Integer.parseInt(leaf.group(2));
            String item = n >= 1 && n <= names.size() ? names.get(n - 1) : "item " + n;
            sb.append("Below is the material, and a reader is being told about one item in it.\n\n")
              .append(fenced(subject)).append("\n\nThe item: \"").append(item).append("\"\n\n")
              .append("Sentences already written for this item:\n").append(written(fileText, leaf.group(1) + "/" + n + "/"));
        } else {
            sb.append("Below is the material that is about to be introduced to a reader.\n\n")
              .append(fenced(subject)).append("\n");
        }
        sb.append("\nInstruction: ").append(blank.instruction()).append("\n");
        if (!blank.sample().isBlank()) {
            sb.append("Model sentence (from a different document; do not copy it, write this item's own content "
                    + "in the same role): ").append(blank.sample()).append("\n");
        }
        sb.append("\nWrite only the sentence(s) asked for: no preface, explanation, heading, quotation marks or bullet marks. "
                + "Write in ").append(language).append(", and end every sentence with the full stop of that language.");
        return sb.toString();
    }

    /** @return the item names in a reply: one per line, numbering and bullets and a trailing full stop removed */
    public List<String> parseNames(String reply, int maxItems) {
        List<String> names = new ArrayList<>();
        for (String line : reply.split("\\R")) {
            String name = line.strip().replaceFirst("^[-*・\\d.)）\\s]+", "").replaceFirst("[。.]$", "").strip();
            if (!name.isEmpty()) names.add(name);
            if (names.size() == maxItems) break;
        }
        return names;
    }

    private String written(String fileText, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (RuleFile.Rule r : RuleFile.parse(fileText).rules()) {
            if (r.name().startsWith(prefix) && r.isSentence()) {
                sb.append("- ").append(r.name().substring(prefix.length())).append(": ")
                  .append(r.items().get(0).value()).append('\n');
            }
        }
        return sb.length() == 0 ? "(none yet)\n" : sb.toString();
    }

    private static String fenced(String subject) {
        return "```\n" + subject.stripTrailing() + "\n```";
    }

    private static String form(String name, String body) {
        return "(defrule " + name + " -> " + body + ")";
    }

    private static String form(String name, List<RuleFile.Token> items) {
        StringBuilder sb = new StringBuilder();
        for (RuleFile.Token t : items) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t.raw());
        }
        return form(name, sb.toString());
    }

    private static List<RuleFile.Token> renameSymbols(List<RuleFile.Token> items, String root, String copyRoot) {
        List<RuleFile.Token> out = new ArrayList<>();
        for (RuleFile.Token t : items) {
            out.add(t.kind() == RuleFile.Token.Kind.SYMBOL
                    ? new RuleFile.Token(t.kind(), rename(t.raw(), root, copyRoot)) : t);
        }
        return out;
    }

    private static String rename(String name, String root, String copyRoot) {
        if (name.equals(root)) return copyRoot;
        if (name.startsWith(root + "/")) return copyRoot + name.substring(root.length());
        return name;
    }

    /** @return the attributes of a blank that stay when it is filled or expanded, with a leading space; {@code ""} when none */
    private static String restOf(List<RuleFile.Token> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            RuleFile.Token t = items.get(i);
            if (t.kind() == RuleFile.Token.Kind.KEYWORD
                    && (t.raw().equals(":instruction") || t.raw().equals(":sample") || t.raw().equals(":frame"))) {
                i++;
                continue;
            }
            sb.append(' ').append(t.raw());
        }
        return sb.toString();
    }

    /** Joins the lines of a sentence: no space where a Japanese character meets the break, one space elsewhere. */
    private static String oneLine(String text) {
        String[] lines = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String l = line.strip();
            if (l.isEmpty()) continue;
            if (sb.length() > 0 && !isCjk(sb.charAt(sb.length() - 1)) && !isCjk(l.charAt(0))) sb.append(' ');
            sb.append(l);
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || "、。，．「」（）".indexOf(c) >= 0;
    }
}
