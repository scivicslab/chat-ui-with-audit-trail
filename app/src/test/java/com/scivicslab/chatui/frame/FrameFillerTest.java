package com.scivicslab.chatui.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("フレームを埋める（モデルも、ワークフローも使わない部分）")
class FrameFillerTest {

    private static final String REQUEST = """
            (in-package :rst)
            (defdocument GenTest_000000_oo01)
            ; the document
            (defrule document -> title sections)
            (defrule title -> "Squares")
            (defrule sections -> s1)
            (defrule s1 -> p-whole concepts :heading "## A Simple Squares Program")

            (defrule p-whole -> :nucleus p-whole/n :example p-whole/code)
            (defrule p-whole/n -> :instruction "Say in 1 or 2 sentences what the program does")
            (defrule p-whole/code -> :file "Squares.java" :kind code-block)

            (defrule concepts -> :instruction "List the concepts" :frame "concept.lisp" :heading "### Concepts")
            """;

    private static final String FRAME = """
            ;;; one concept
            (defrule concept -> :nucleus concept/n :definition concept/def)
            (defrule concept/n -> :instruction "Say where the concept is written in the code"
              :sample "The program declares a method called \\"main\\".")
            (defrule concept/def -> :instruction "Define the concept"
              :sample "A method is a block of code that does one task.")
            """;

    private final FrameFiller filler = new FrameFiller();

    @Test
    @DisplayName("読んで書き戻すと、元の文字列と同じになる（コメントも空行も）")
    void aRuleFileRoundTripsByteForByte() {
        assertEquals(REQUEST, RuleFile.parse(REQUEST).text());
        assertEquals(FRAME, RuleFile.parse(FRAME).text());
    }

    @Test
    @DisplayName("複数行にまたがる規則も、1 つの規則として読み、:sample の中の引用符を取り違えない")
    void aRuleOverSeveralLinesIsOneRule() {
        RuleFile.Rule n = RuleFile.parse(FRAME).rule("concept/n");
        assertEquals("Say where the concept is written in the code", n.attr(":instruction"));
        assertEquals("The program declares a method called \"main\".", n.attr(":sample"));
        assertFalse(n.isSentence());
    }

    @Test
    @DisplayName("最初の空欄は、:instruction を持つ最初の規則")
    void theNextBlankIsTheFirstRuleThatCarriesAnInstruction() {
        FrameFiller.Blank b = filler.next(REQUEST);
        assertEquals("p-whole/n", b.name());
        assertFalse(b.isFrame());
        assertEquals("", b.sample());
    }

    @Test
    @DisplayName("空欄を埋めると、その規則だけが文に替わり、ほかは変わらない")
    void fillingABlankChangesOnlyThatRule() {
        String filled = filler.fill(REQUEST, "p-whole/n", "It prints the squares.");

        assertTrue(filled.contains("(defrule p-whole/n -> \"It prints the squares.\")"));
        assertEquals(REQUEST.replace("(defrule p-whole/n -> :instruction \"Say in 1 or 2 sentences what the program does\")",
                "(defrule p-whole/n -> \"It prints the squares.\")"), filled);
        assertEquals("concepts", filler.next(filled).name());
    }

    @Test
    @DisplayName("文の中の引用符と円記号は、規則の文字列として正しく書かれ、読み戻せる")
    void quotesAndBackslashesInASentenceSurvive() {
        String sentence = "He said \"hi\" and used a \\ backslash.";
        String filled = filler.fill(REQUEST, "p-whole/n", sentence);
        assertEquals(sentence, RuleFile.parse(filled).rule("p-whole/n").items().get(0).value());
    }

    @Test
    @DisplayName("改行を含む文は 1 行にする。空白を入れるのは、英数字どうしが改行でつながる所だけ")
    void aSentenceOverSeveralLinesBecomesOne() {
        String filled = filler.fill(REQUEST, "p-whole/n", "整数の平方を\n計算する。\nIt prints\nthem.");
        assertEquals("整数の平方を計算する。It prints them.", RuleFile.parse(filled).rule("p-whole/n").items().get(0).value());
    }

    @Test
    @DisplayName(":frame の空欄は、項目の数だけの写しに展開され、見出しは残る")
    void aFrameBlankBecomesOneCopyPerItem() {
        String filled = filler.fill(REQUEST, "p-whole/n", "It prints the squares.");
        String expanded = filler.expand(filled, "concepts", FRAME, List.of("ArrayList", "for loop"));

        RuleFile file = RuleFile.parse(expanded);
        assertEquals(List.of("concepts/1", "concepts/2"),
                file.rule("concepts").items().stream().filter(t -> t.kind() == RuleFile.Token.Kind.SYMBOL).map(RuleFile.Token::raw).toList());
        assertEquals("### Concepts", file.rule("concepts").attr(":heading"));
        assertNull(file.rule("concepts").attr(":instruction"));
        assertNull(file.rule("concepts").attr(":frame"));
        assertNotNull(file.rule("concepts/1"));
        assertNotNull(file.rule("concepts/2/def"));
        assertFalse(expanded.contains("concept/n"), "the frame's own names are not left in the copies");
        assertTrue(file.rule("concepts/1").items().stream().anyMatch(t -> t.raw().equals("concepts/1/n")));
    }

    @Test
    @DisplayName("展開のあとの空欄は、写しの葉が、枠のファイルの順に並ぶ")
    void afterExpansionTheLeavesComeInTheFramesOrder() {
        String text = filler.expand(REQUEST, "concepts", FRAME, List.of("A", "B"));
        List<String> order = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            FrameFiller.Blank b = filler.next(text);
            if (b == null) break;
            order.add(b.name());
            text = filler.fill(text, b.name(), "x。");
        }
        assertEquals(List.of("p-whole/n", "concepts/1/n", "concepts/1/def", "concepts/2/n", "concepts/2/def"), order);
        assertNull(filler.next(text));
    }

    @Test
    @DisplayName("展開できないものは、理由つきで断る")
    void expansionRefusesWhatItCannotDo() {
        assertThrows(IllegalArgumentException.class, () -> filler.expand(REQUEST, "p-whole/n", FRAME, List.of("A")));
        assertThrows(IllegalArgumentException.class, () -> filler.expand(REQUEST, "concepts", FRAME, List.of()));
        assertThrows(IllegalArgumentException.class, () -> filler.fill(REQUEST, "no-such-rule", "x"));
    }

    @Test
    @DisplayName("項目の一覧の返事から、番号・記号・句点を除いて名前を取り出し、個数で打ち切る")
    void itemNamesAreReadFromAList() {
        assertEquals(List.of("ArrayList", "forループ", "標準出力"),
                filler.parseNames("1. ArrayList\n- forループ。\n\n  ・標準出力\n4) 切り捨て", 3));
    }

    @Test
    @DisplayName("写しの葉の依頼には、項目の名前、同じ写しですでに書いた文、お手本が入る")
    void aLeafOfACopyIsAskedAboutItsItem() {
        String text = filler.expand(REQUEST, "concepts", FRAME, List.of("ArrayList", "for loop"));
        text = filler.fill(text, "p-whole/n", "It prints the squares.");
        text = filler.fill(text, "concepts/1/n", "The list is made in main.");
        text = filler.fill(text, "concepts/1/def", "A list holds items in order.");
        text = filler.fill(text, "concepts/2/n", "The for statement is in main.");
        FrameFiller.Blank b = filler.next(text);
        assertEquals("concepts/2/def", b.name());

        String prompt = filler.sentencePrompt(text, b, "class Squares {}", List.of("ArrayList", "for loop"), "Japanese");

        assertTrue(prompt.contains("The item: \"for loop\""), prompt);
        assertTrue(prompt.contains("- n: The for statement is in main."), prompt);
        assertFalse(prompt.contains("ArrayList"), "the other item must not leak in");
        assertTrue(prompt.contains("Instruction: Define the concept"), prompt);
        assertTrue(prompt.contains("A method is a block of code that does one task."), prompt);
        assertTrue(prompt.contains("class Squares {}"), prompt);
    }

    @Test
    @DisplayName("全体についての空欄の依頼には、項目の話も、すでに書いた文の一覧も入らない")
    void aWholeDocumentBlankIsNotAskedAboutAnItem() {
        String prompt = filler.sentencePrompt(REQUEST, filler.next(REQUEST), "class Squares {}", List.of(), "Japanese");
        assertFalse(prompt.contains("The item"), prompt);
        assertFalse(prompt.contains("already written"), prompt);
        assertTrue(prompt.contains("Say in 1 or 2 sentences what the program does"));
    }

    @Test
    @DisplayName("項目の一覧の依頼は、名前だけを求め、個数の上限を伝える")
    void theListPromptAsksForNamesOnly() {
        FrameFiller.Blank frame = filler.next(filler.fill(REQUEST, "p-whole/n", "x"));
        String prompt = filler.listPrompt(frame, "class Squares {}", 5, "Japanese");
        assertTrue(prompt.contains("at most 5 items"), prompt);
        assertTrue(prompt.contains("List the concepts"), prompt);
        assertTrue(prompt.contains("No numbering"), prompt);
    }

    @Test
    @DisplayName("段落の空欄の依頼には、同じ段落ですでに書いた文が入り、ほかの段落の文は入らない")
    void aParagraphBlankIsShownWhatItsParagraphAlreadySays() {
        String text = """
            (defrule s1 -> p-whole p-run)
            (defrule p-whole -> :nucleus p-whole/n)
            (defrule p-whole/n -> "The program prints five squares.")
            (defrule p-run -> :nucleus p-run/n :example p-run/code :result p-run/shows)
            (defrule p-run/n -> "You run the program with this command:")
            (defrule p-run/code -> "java Squares.java")
            (defrule p-run/shows -> :instruction "Say what the program displays")
            """;
        String prompt = filler.sentencePrompt(text, filler.next(text), "class Squares {}", List.of(), "Japanese");
        assertTrue(prompt.contains("You run the program with this command:"), prompt);
        assertTrue(prompt.contains("java Squares.java"), prompt);
        assertFalse(prompt.contains("The program prints five squares."), "another paragraph's sentence: " + prompt);
        assertTrue(prompt.contains("Say what the program displays"), prompt);
    }
}
