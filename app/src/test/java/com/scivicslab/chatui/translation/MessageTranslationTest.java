package com.scivicslab.chatui.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The languages a message can be translated into, and the instruction it is sent under. */
@Tag("MessageTranslation_261011_oo01")
@DisplayName("会話の 1 件を訳す")
class MessageTranslationTest {

    @Test
    @DisplayName("言語は英語と English Toolkit の 12 で、同じ順・同じコード")
    void languagesAreEnglishThenTheEnglishToolkitTwelve() {
        List<String> codes = TranslationLanguages.ALL.stream().map(TranslationLanguages.Language::code).toList();
        assertEquals(List.of("en", "ja", "zh-Hans", "zh-Hant", "ko", "hi", "es", "ar", "fr", "pt", "ru", "id", "de"), codes);
        assertEquals("Chinese (Traditional)", TranslationLanguages.find("zh-Hant").orElseThrow().name());
    }

    @Test
    @DisplayName("知らないコードは見つからない")
    void unknownCodeIsNotFound() {
        assertTrue(TranslationLanguages.find("xx").isEmpty());
        assertTrue(TranslationLanguages.find(null).isEmpty());
        assertEquals("ja", TranslationLanguages.find(" JA ").orElseThrow().code(), "case and spaces do not matter");
    }

    @Test
    @DisplayName("指示文に言語名が入り、訳す文がそのまま最後に付く")
    void promptNamesTheLanguageAndEndsWithTheText() {
        String text = "## 見出し\n`a.b()` を呼ぶ。";
        String prompt = TranslationPrompt.of(text, "English");
        assertTrue(prompt.startsWith("Translate the following text into natural English."), prompt);
        assertTrue(prompt.contains("Reply with ONLY the English translation"), prompt);
        assertTrue(prompt.endsWith("\n\n" + text), prompt);
    }
}
