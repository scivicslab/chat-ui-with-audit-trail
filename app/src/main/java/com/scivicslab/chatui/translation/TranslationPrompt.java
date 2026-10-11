package com.scivicslab.chatui.translation;

/**
 * The instruction one message is translated under: English Toolkit's, without its assumption that
 * the text is English, and with what a conversation's message carries that a sentence does not —
 * markdown, code and paths, which must come back unchanged.
 */
public final class TranslationPrompt {

    private TranslationPrompt() {}

    /**
     * @param text     the message, unchanged
     * @param language the target language's English name, e.g. {@code Japanese}
     * @return the whole instruction, ending with the text
     */
    public static String of(String text, String language) {
        return "Translate the following text into natural " + language + ".\n"
                + "Keep markdown formatting, code blocks, file paths and identifiers as they are.\n"
                + "Reply with ONLY the " + language + " translation — no preamble, no explanation.\n\n"
                + text;
    }
}
