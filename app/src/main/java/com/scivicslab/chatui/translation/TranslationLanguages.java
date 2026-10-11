package com.scivicslab.chatui.translation;

import java.util.List;
import java.util.Optional;

/**
 * The languages one message of a conversation can be translated into
 * ({@code MessageTranslation_261011_oo01}): English, then English Toolkit's twelve, in its order and
 * with its BCP 47 codes. English Toolkit has no English because it translates English sentences; a
 * conversation here is as often in Japanese, so English comes first.
 */
public final class TranslationLanguages {

    /** A BCP 47 code, and the English name the instruction and the screen use. */
    public record Language(String code, String name) {}

    public static final List<Language> ALL = List.of(
            new Language("en", "English"),
            new Language("ja", "Japanese"),
            new Language("zh-Hans", "Chinese (Simplified)"),
            new Language("zh-Hant", "Chinese (Traditional)"),
            new Language("ko", "Korean"),
            new Language("hi", "Hindi"),
            new Language("es", "Spanish"),
            new Language("ar", "Arabic"),
            new Language("fr", "French"),
            new Language("pt", "Portuguese"),
            new Language("ru", "Russian"),
            new Language("id", "Indonesian"),
            new Language("de", "German"));

    private TranslationLanguages() {}

    /** @return the language with this code, ignoring case and surrounding spaces; empty for any other */
    public static Optional<Language> find(String code) {
        if (code == null) return Optional.empty();
        String c = code.strip();
        return ALL.stream().filter(l -> l.code().equalsIgnoreCase(c)).findFirst();
    }
}
