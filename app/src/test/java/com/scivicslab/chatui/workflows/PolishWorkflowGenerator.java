package com.scivicslab.chatui.workflows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the two polish workflows that are shipped with this program.
 *
 * <p>Both are one shape repeated: seven criteria, six transitions each, differing only in the
 * criterion's name and the standard it reads. Writing those forty-two transitions by hand means
 * copying them again whenever a criterion is added, so this is the source and
 * {@code app/src/main/resources/workflows/polish-by-checklist.yaml} and {@code polish-files.yaml}
 * are its output ({@code WorkflowFileLifecycle_260914_oo01}).</p>
 *
 * <p>Run it by hand after changing a criterion or the shape of a transition, and commit the
 * generated YAML with the change — the build does not run this:</p>
 * <pre>
 *   mvn -o test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.scivicslab.chatui.workflows.PolishWorkflowGenerator
 * </pre>
 *
 * <p>The prompts are Japanese because the documents being worked on are, and they are data rather
 * than messages of this program.</p>
 */
public final class PolishWorkflowGenerator {

    /** Where the standards the criteria read live. */
    private static final String STD =
            "/home/devteam/works/doc_Base010/docs/ProjectStandard/010_ProjectStandards";

    /** Where the generated workflows are written. */
    private static final String BUNDLED =
            "/home/devteam/works/chat-ui-with-audit-trail/app/src/main/resources/workflows";

    /**
     * One item of the checklist.
     *
     * @param name    what the transitions of this criterion are labelled with
     * @param ruleDoc the standard the fixer and the judge are handed
     * @param deletes whether this criterion removes things, which is what makes its judge compare
     *                against the text as it was and demand that every fact survives
     */
    private record Criterion(String name, String ruleDoc, boolean deletes) {}

    private static final List<Criterion> CRITERIA = List.of(
            new Criterion("subject", STD + "/080_OopSubjectClarity_260810_oo01/080_OopSubjectClarity_260810_oo01.md", false),
            new Criterion("naming", STD + "/023_NamingByTypeAndInstance_260628_oo01/023_NamingByTypeAndInstance_260628_oo01.md", false),
            new Criterion("thread", STD + "/120_ThreadAndAsides_260913_oo01/120_ThreadAndAsides_260913_oo01.md", true),
            new Criterion("negation", STD + "/110_NegationOnlyWhenExpected_260913_oo01/110_NegationOnlyWhenExpected_260913_oo01.md", true),
            new Criterion("oneaxis", STD + "/130_OneAxisPerSection_260913_oo01/130_OneAxisPerSection_260913_oo01.md", false),
            new Criterion("list", STD + "/140_IndependentBullets_260913_oo01/140_IndependentBullets_260913_oo01.md", false),
            new Criterion("sections", STD + "/150_DocumentSections_260913_oo01/150_DocumentSections_260913_oo01.md", false));

    /** Said to the fixer in every criterion: what a rewrite may not do to the text it is given. */
    private static final String STYLE =
            "本文の文体を変えないでください。ですます体で書かれていればですます体のまま、である体で"
            + "書かれていればである体のまま直してください。規則の文書の文体に合わせないでください。"
            + "実体に名前を与えるときは、短い名詞句にしてください。説明文をそのまま名前にしないでください。"
            + "名前を置き換えたら、表の列や本文が読めるかを確かめてください。";

    private static final String NOADD =
            "本文にある事実・数値・コマンド・識別子は書き換えないでください。"
            + "節を足す必要があるときは、元の本文にある事実だけを使って書いてください。"
            + "他の文書から文をそのまま持ち込まないでください。他の文書にある説明は、その文書を参照する一文で足ります。"
            + "確かめていないコマンド・実行結果・設定値を書かないでください。";

    /** Said to the criteria that remove things: a fact that leaves the thread is moved, not lost. */
    private static final String KEEP =
            "事実を削らないでください。筋から外れると判断した事実は、本文から外したうえで、"
            + "末尾の「## 雑記」の節に箇条書きで移してください（順不同でかまいません）。"
            + "「## 参考資料」の節があれば、その直前に「## 雑記」を置いてください。";

    private static final String VERIFY_JUDGE =
            "\"元の本文と直した本文を見比べて、次の2つだけを見てください。"
            + "(1) 元の本文に無いコマンド・実行結果・数値・設定値が、直した本文に入っていないか。"
            + "(2) 元の本文にあった数値・コマンド・識別子が書き換えられていないか。"
            + "どちらも無ければ ACCEPT の一語だけを返してください。"
            + "あれば REVISE: に続けて、その箇所を一つずつ挙げてください。"
            + "文章の善し悪しや、規則を満たしているかは見ないでください。ほかのことは書かないでください。"
            + "\\n\\n元の本文:\\n\" + state.getString(\"original\") + \"\\n\\n直した本文:\\n\" + state.getString(\"text\")";

    private static final String VERIFY_FIX =
            "\"指摘を受けました。指摘された箇所だけを直してください。直した本文だけを返してください。"
            + "元の本文に無いコマンド・実行結果・数値は消してください。"
            + "他の文書にある説明をそのまま持ち込んでいる箇所は、その文書を参照する一文に置き換えてください。"
            + "元の本文にあった数値・コマンド・識別子が書き換えられていれば、元の値に戻してください。"
            + "それ以外は今の本文のままにしてください。"
            + "\\n\\n指摘:\\n\" + state.getString(\"verdict\") + \"\\n\\n元の本文:\\n\" + state.getString(\"original\")"
            + " + \"\\n\\nいまの本文:\\n\" + state.getString(\"text\")";

    /** The two conversations are emptied before each turn, so no prompt inherits the last one. */
    private static final String CLEAR =
            "      - actor: this\n        method: clearWorker\n"
            + "        arguments: [\"fixer\"]\n        execution: direct\n"
            + "      - actor: this\n        method: clearWorker\n"
            + "        arguments: [\"judge\"]\n        execution: direct\n";

    public static void main(String[] args) throws IOException {
        Files.writeString(Path.of(BUNDLED, "polish-by-checklist.yaml"), build(false));
        Files.writeString(Path.of(BUNDLED, "polish-files.yaml"), build(true));
        System.out.println("written");
    }

    /** One turn: the prompt goes to a conversation, whose reply is picked up by the caller. */
    private static String ask(String expression, String slot) {
        return "      - actor: this\n        method: askWorker\n"
                + "        arguments: [\"" + slot + "\", 'jexl:" + expression + "']\n"
                + "        execution: direct\n";
    }

    /** Said to both roles when the rule and the text are files rather than text in the prompt. */
    private static final String READ_FIRST =
            "規則の文書と本文は、それぞれファイルにある。read ツールで両方を読んでから答えること。";

    private static String judgePromptByPath(Criterion c, boolean deletes) {
        String head = "\"" + READ_FIRST
                + (deletes
                   ? "直した本文が規則を満たしているか、そして元の本文にあった事実が全部残っているかを見る。"
                     + "事実は、本文の中か「## 雑記」の節のどちらかに残っていればよく、場所が変わっただけなら問題ない。"
                     + "両方を満たしていれば ACCEPT の一語だけを返す。"
                     + "規則を満たしていなければ REVISE: に続けて直っていない箇所を挙げる。"
                     + "元の本文にあった事実が消えていれば、REVISE: に続けて「削りすぎ」と書き、消えた事実を一つずつ挙げる。"
                   : "本文がこの規則を満たしているかだけを見る。規則のうち、この本文に当てはまる項目だけで判断する。"
                     + "本文に無い事柄が書かれていないことを理由に REVISE と判断しない。"
                     + "満たしていれば ACCEPT の一語だけを返す。"
                     + "満たしていなければ REVISE: に続けて、満たしていない箇所を具体的に挙げる。")
                + "ほかのことは書かない。"
                + "\\n\\n規則: " + c.ruleDoc()
                + "\\n本文: \" + state.getString(\"work\")";
        return deletes ? head + " + \"\\n元の本文: \" + state.getString(\"before-path\")" : head;
    }

    private static String redoPromptByPath(Criterion c, boolean deletes) {
        return "\"" + READ_FIRST
                + "指摘された点を直し、write ツールで本文のファイルに同じパスで書き戻すこと。"
                + "直した本文を返事に書かなくてよい。書き戻したら「done」とだけ返す。"
                + (deletes ? KEEP : "") + NOADD + STYLE
                + "\\n\\n規則: " + c.ruleDoc()
                + "\\n本文: \" + state.getString(\"work\")"
                + (deletes ? " + \"\\n元の本文（ここにある事実は全部残すこと）: \" + state.getString(\"before-path\")" : "")
                + " + \"\\n\\n指摘:\\n\" + state.getString(\"verdict\")";
    }

    private static final String VERIFY_FIX_BY_PATH =
            "\"" + READ_FIRST
            + "指摘された箇所だけを直し、write ツールで直した本文のファイルに同じパスで書き戻すこと。"
            + "元の本文に無いコマンド・実行結果・数値は消す。"
            + "元の本文にあった数値・コマンド・識別子が書き換えられていれば、元の値に戻す。"
            + "それ以外は今のままにする。書き戻したら「done」とだけ返す。"
            + "\\n\\n元の本文: \" + state.getString(\"orig\") + \"\\n直した本文: \" + state.getString(\"work\")"
            + " + \"\\n\\n指摘:\\n\" + state.getString(\"verdict\")";

    /** Puts what the conversation wrote back into the plan's own state. */
    private static final String TAKE_BACK_THE_FILE =
            "      - actor: this\n        method: readFile\n"
            + "        arguments: ['jexl:state.getString(\"work\")', \"text\"]\n        execution: direct\n";

    /** Hands the conversation the text as it stands, as a file it can read and write. */
    private static final String PUT_THE_TEXT_IN_ITS_FILE =
            "      - actor: this\n        method: writeFile\n"
            + "        arguments: ['jexl:state.getString(\"work\")', \"text\"]\n        execution: direct\n";

    private static String rule(int index) {
        return "state.getString(\"rule-" + index + "\")";
    }

    private static String fixPrompt(int index, boolean deletes) {
        return "\"次の規則だけに従って本文を直してください。規則に書かれていない観点では直さないでください。"
                + "規則のうち、この本文に当てはまる項目だけを直してください。本文に書かれていない事柄を足すことは求めていません。"
                + NOADD + STYLE
                + "直した本文だけを返してください。" + (deletes ? KEEP : "") + "\\n\\n規則:\\n\" + "
                + rule(index) + " + \"\\n\\n本文:\\n\" + state.getString(\"text\")";
    }

    private static String redoPrompt(int index, boolean deletes) {
        if (deletes) {
            return "\"指摘を受けました。同じ規則のまま直してください。直した本文だけを返してください。"
                    + "指摘が「削りすぎ」であれば、消えた事実を元の本文から取り戻し、本文か「## 雑記」の節に書き戻してください。"
                    + NOADD + STYLE + KEEP + "\\n\\n規則:\\n\" + " + rule(index)
                    + " + \"\\n\\n指摘:\\n\" + state.getString(\"verdict\") + \"\\n\\n元の本文（ここにある事実は全部残すこと）:\\n\""
                    + " + state.getString(\"before\") + \"\\n\\nいまの本文:\\n\" + state.getString(\"text\")";
        }
        return "\"指摘を受けました。同じ規則のまま直してください。直した本文だけを返してください。"
                + NOADD + STYLE + "\\n\\n規則:\\n\" + " + rule(index)
                + " + \"\\n\\n指摘:\\n\" + state.getString(\"verdict\") + \"\\n\\n本文:\\n\" + state.getString(\"text\")";
    }

    private static String judgePrompt(int index, boolean deletes) {
        if (deletes) {
            return "\"直した本文が次の規則を満たしているか、そして元の本文にあった事実が全部残っているかを見てください。"
                    + "規則のうち、この本文に当てはまる項目だけで判断してください。本文に無い事柄が書かれていないことを理由に REVISE と判断しないでください。"
                    + "事実は、本文の中か「## 雑記」の節のどちらかに残っていればよく、場所が変わっただけなら問題ありません。"
                    + "両方を満たしていれば ACCEPT の一語だけを返してください。"
                    + "規則を満たしていなければ REVISE: に続けて直っていない箇所を挙げてください。"
                    + "元の本文にあった事実が消えていれば、REVISE: に続けて「削りすぎ」と書き、消えた事実を一つずつ挙げてください。"
                    + "ほかのことは書かないでください。\\n\\n規則:\\n\" + " + rule(index)
                    + " + \"\\n\\n元の本文:\\n\" + state.getString(\"before\") + \"\\n\\n直した本文:\\n\" + state.getString(\"text\")";
        }
        return "\"次の本文が、この規則を満たしているかだけを見てください。規則のうち、この本文に当てはまる項目だけで判断してください。"
                + "本文に無い事柄が書かれていないことを理由に REVISE と判断しないでください。"
                + "満たしていれば ACCEPT の一語だけを返してください。"
                + "満たしていなければ REVISE: に続けて、満たしていない箇所を具体的に挙げてください。ほかのことは書かないでください。"
                + "\\n\\n規則:\\n\" + " + rule(index) + " + \"\\n\\n本文:\\n\" + state.getString(\"text\")";
    }

    /** The six transitions of one criterion: read the rule, judge, accept, send back, give up, fix. */
    private static String block(int i, Criterion c, String next, boolean byReference) {
        if (byReference) {
            return blockByReference(i, c, next);
        }
        return blockByValue(i, c, next);
    }

    /**
     * One criterion, with the rule and the text named by path rather than carried in the prompt.
     *
     * <p>A prompt that carries a 9 KB standard and a 9 KB document is 15,000 characters, and the
     * history it leaves behind has to be thrown away every criterion — which is what made the work
     * invisible on screen ({@code PromptByValueHidesTheWork_260915_oo01}). Named by path, the
     * prompt is a few hundred characters, the conversation reads what it needs with its own tools,
     * and the turn keeps only the question and the answer. Nothing is cleared, so the conversation
     * reads as what it is: someone working through a checklist.</p>
     */
    private static String blockByReference(int i, Criterion c, String next) {
        String snapshot = c.deletes()
                ? "      - actor: this\n        method: copyState\n"
                  + "        arguments: [\"text\", \"before\"]\n        execution: direct\n"
                  + "      - actor: this\n        method: writeFile\n"
                  + "        arguments: ['jexl:state.getString(\"before-path\")', \"before\"]\n"
                  + "        execution: direct\n"
                : "";
        String ruleFile = c.ruleDoc().substring(c.ruleDoc().lastIndexOf('/') + 1);
        return "\n  # ── " + i + ". " + c.name() + " ───────────────────────────────────────────────────────────\n"
                + "  - states: [\"enter-" + i + "\", \"judge-" + i + "\"]\n"
                + "    label: enter-" + i + "-" + c.name() + "\n"
                + "    note: |\n"
                + "      規則は " + ruleFile + " にある。会話がそれを読む。\n"
                + "      いまの本文を控え、会話が読み書きするファイルへ置く。ここではまだ本文を書き換えない。\n"
                + "    actions:\n"
                + snapshot
                + "      - actor: this\n        method: copyState\n"
                + "        arguments: [\"text\", \"kept-" + i + "\"]\n        execution: direct\n"
                + PUT_THE_TEXT_IN_ITS_FILE
                + "\n  - states: [\"judge-" + i + "\", \"check-" + i + "\"]\n"
                + "    label: judge-" + i + "-" + c.name() + "\n"
                + "    note: 本文がこの規則を満たしているかを見る。満たしていれば本文には触れない。\n"
                + "    actions:\n"
                + ask(judgePromptByPath(c, c.deletes()), "judge")
                + "      - actor: this\n        method: keepWorkerReply\n"
                + "        arguments: [\"judge\", \"verdict\"]\n        execution: direct\n"
                + "\n  - states: [\"check-" + i + "\", \"" + next + "\"]\n"
                + "    label: accept-" + i + "\n"
                + "    note: 満たしている。本文はそのまま次の規則へ。\n"
                + "    actions: [{actor: this, method: checkState, arguments: [\"verdict\", \"ACCEPT\"], execution: direct}]\n"
                + "\n  - states: [\"check-" + i + "\", \"fix-" + i + "\"]\n"
                + "    label: needs-fix-" + i + "\n"
                + "    note: 満たしていない。直す回数が残っていれば直しへ。\n"
                + "    actions: [{actor: this, method: countUp, arguments: [\"tries-" + i + "\", 'jexl:state.getString(\"tries\")'], execution: direct}]\n"
                + "\n  - states: [\"check-" + i + "\", \"" + next + "\"]\n"
                + "    label: give-up-" + i + "\n"
                + "    note: |\n"
                + "      直す回数を使い切った。この観点に入る前の本文に戻して次の観点へ進む。満たせなかった観点の\n"
                + "      書き換えを残すと、いじり回しただけの本文になる。\n"
                + "    actions: [{actor: this, method: copyState, arguments: [\"kept-" + i + "\", \"text\"], execution: direct}]\n"
                + "\n  - states: [\"fix-" + i + "\", \"judge-" + i + "\"]\n"
                + "    label: fix-" + i + "-" + c.name() + "\n"
                + "    note: 会話がファイルを直し、書き戻したものをこの計画が読み取る。\n"
                + "    actions:\n"
                + ask(redoPromptByPath(c, c.deletes()), "fixer")
                + TAKE_BACK_THE_FILE;
    }

    private static String blockByValue(int i, Criterion c, String next) {
        String load = "      - actor: this\n        method: readFile\n"
                + "        arguments: [\"" + c.ruleDoc() + "\", \"rule-" + i + "\"]\n"
                + "        execution: direct\n";
        String snapshot = c.deletes()
                ? "      - actor: this\n        method: copyState\n"
                  + "        arguments: [\"text\", \"before\"]\n        execution: direct\n"
                : "";
        String ruleFile = c.ruleDoc().substring(c.ruleDoc().lastIndexOf('/') + 1);
        return "\n  # ── " + i + ". " + c.name() + " ───────────────────────────────────────────────────────────\n"
                + "  - states: [\"enter-" + i + "\", \"judge-" + i + "\"]\n"
                + "    label: enter-" + i + "-" + c.name() + "\n"
                + "    note: |\n"
                + "      規則は " + ruleFile + " にある。\n"
                + "      規則を読み、いまの本文を控える。ここではまだ本文を書き換えない。\n"
                + "    actions:\n"
                + load + snapshot
                + "      - actor: this\n        method: copyState\n"
                + "        arguments: [\"text\", \"kept-" + i + "\"]\n        execution: direct\n"
                + "\n  - states: [\"judge-" + i + "\", \"check-" + i + "\"]\n"
                + "    label: judge-" + i + "-" + c.name() + "\n"
                + "    note: 本文がこの規則を満たしているかを見る。満たしていれば本文には触れない。\n"
                + "    actions:\n"
                + CLEAR + ask(judgePrompt(i, c.deletes()), "judge")
                + "      - actor: this\n        method: keepWorkerReply\n"
                + "        arguments: [\"judge\", \"verdict\"]\n        execution: direct\n"
                + "\n  - states: [\"check-" + i + "\", \"" + next + "\"]\n"
                + "    label: accept-" + i + "\n"
                + "    note: 満たしている。本文はそのまま次の規則へ。\n"
                + "    actions: [{actor: this, method: checkState, arguments: [\"verdict\", \"ACCEPT\"], execution: direct}]\n"
                + "\n  - states: [\"check-" + i + "\", \"fix-" + i + "\"]\n"
                + "    label: needs-fix-" + i + "\n"
                + "    note: 満たしていない。直す回数が残っていれば直しへ。\n"
                + "    actions: [{actor: this, method: countUp, arguments: [\"tries-" + i + "\", 'jexl:state.getString(\"tries\")'], execution: direct}]\n"
                + "\n  - states: [\"check-" + i + "\", \"" + next + "\"]\n"
                + "    label: give-up-" + i + "\n"
                + "    note: |\n"
                + "      直す回数を使い切った。この観点に入る前の本文に戻して次の観点へ進む。満たせなかった観点の\n"
                + "      書き換えを残すと、いじり回しただけの本文になる。\n"
                + "    actions: [{actor: this, method: copyState, arguments: [\"kept-" + i + "\", \"text\"], execution: direct}]\n"
                + "\n  - states: [\"fix-" + i + "\", \"judge-" + i + "\"]\n"
                + "    label: fix-" + i + "-" + c.name() + "\n"
                + "    actions:\n"
                + CLEAR + ask(redoPrompt(i, c.deletes()), "fixer")
                + "      - actor: this\n        method: keepWorkerReply\n"
                + "        arguments: [\"fixer\", \"text\"]\n        execution: direct\n";
    }

    private static String verifyBlock(String next, boolean byReference) {
        if (!byReference) return verifyBlock(next);
        return "\n  # ── last: nothing brought in, nothing altered ─────────────────────────────\n"
                + "  - states: [\"verify\", \"verify-check\"]\n"
                + "    label: verify-nothing-was-brought-in\n"
                + "    note: |\n"
                + "      Compares the text as it arrived with the text as it stands, by comparing rather than by\n"
                + "      asking. A judge given both texts answered ACCEPT for nine documents of nine, one of which\n"
                + "      had had a YAML block rewritten into something that does not run\n"
                + "      (WhatAProgramCanDo_260915_oo01).\n"
                + "    actions:\n"
                + "      - actor: this\n        method: compareTexts\n"
                + "        arguments: [\"original\", \"text\", \"verdict\"]\n        execution: direct\n"
                + "\n  - states: [\"verify-check\", \"" + next + "\"]\n"
                + "    label: accept-verify\n"
                + "    actions: [{actor: this, method: checkState, arguments: [\"verdict\", \"ACCEPT\"], execution: direct}]\n"
                + "\n  - states: [\"verify-check\", \"verify-fix\"]\n"
                + "    label: needs-verify-fix\n"
                + "    actions: [{actor: this, method: countUp, arguments: [\"tries-verify\", 'jexl:state.getString(\"tries\")'], execution: direct}]\n"
                + "\n  - states: [\"verify-check\", \"skip\"]\n"
                + "    label: give-up-verify\n"
                + "    note: |\n"
                + "      The last check could not be passed. This file is not written: what cannot be shown to be\n"
                + "      unbroken does not replace what is there. The original stays as it is, and the job says so.\n"
                + "    actions: [{actor: this, method: doNothing, arguments: [\"not written\"], execution: direct}]\n"
                + "\n  - states: [\"verify-fix\", \"verify\"]\n"
                + "    label: take-back-what-was-brought-in\n"
                + "    actions:\n"
                + ask(VERIFY_FIX_BY_PATH, "fixer")
                + TAKE_BACK_THE_FILE;
    }

    /** The last check on a text: nothing brought in, nothing altered. */
    private static String verifyBlock(String next) {
        return "\n  # ── last: nothing brought in, nothing altered ─────────────────────────────\n"
                + "  - states: [\"verify\", \"verify-check\"]\n"
                + "    label: verify-nothing-was-brought-in\n"
                + "    note: |\n"
                + "      Compares the text as it arrived with the text as it stands. The criteria may add a section,\n"
                + "      but only out of what the text already says: a command, an output or a number that was not\n"
                + "      there, and a value that has changed, are what this looks for.\n"
                + "    actions:\n"
                + CLEAR + ask(VERIFY_JUDGE, "judge")
                + "      - actor: this\n        method: keepWorkerReply\n"
                + "        arguments: [\"judge\", \"verdict\"]\n        execution: direct\n"
                + "\n  - states: [\"verify-check\", \"" + next + "\"]\n"
                + "    label: accept-verify\n"
                + "    actions: [{actor: this, method: checkState, arguments: [\"verdict\", \"ACCEPT\"], execution: direct}]\n"
                + "\n  - states: [\"verify-check\", \"verify-fix\"]\n"
                + "    label: needs-verify-fix\n"
                + "    actions: [{actor: this, method: countUp, arguments: [\"tries-verify\", 'jexl:state.getString(\"tries\")'], execution: direct}]\n"
                + "\n  - states: [\"verify-check\", \"skip\"]\n"
                + "    label: give-up-verify\n"
                + "    note: |\n"
                + "      The last check could not be passed. This file is not written: what cannot be shown to be\n"
                + "      unbroken does not replace what is there. The original stays as it is, and the job says so.\n"
                + "    actions: [{actor: this, method: doNothing, arguments: [\"not written\"], execution: direct}]\n"
                + "\n  - states: [\"verify-fix\", \"verify\"]\n"
                + "    label: take-back-what-was-brought-in\n"
                + "    actions:\n"
                + CLEAR + ask(VERIFY_FIX, "fixer")
                + "      - actor: this\n        method: keepWorkerReply\n"
                + "        arguments: [\"fixer\", \"text\"]\n        execution: direct\n";
    }

    private static final String HEAD_TEXT = """
name: polish-by-checklist
description: |
  Fixes one text against a checklist, one item at a time. Where a written standard exists, the plan
  reads that document and hands it to the fixer and to the judge as the rule — the workflow carries
  the path, not the wording. The criteria that remove things keep every fact: what leaves the thread
  moves to a 雑記 section, and their judges are given the text as it was, to compare.
params:
  text:
    label: "The text"
    description: "What to fix"
    type: textarea
  fixer:
    description: "The conversation that rewrites"
    default: "project1/chat-02"
  judge:
    description: "The conversation that decides whether the rule is met"
    default: "project1/chat-03"
  tries:
    description: "How many times one criterion may be sent back before it is left as it was"
    type: int
    default: 5
  fixerProvider:
    description: "The provider the fixer runs on (openai-compat / claude / codex); empty leaves the conversation as it is"
    default: ""
  fixerModel:
    description: "The model the fixer runs on; empty leaves the conversation as it is"
    default: ""
  judgeProvider:
    description: "The provider the judge runs on; empty leaves the conversation as it is"
    default: ""
  judgeModel:
    description: "The model the judge runs on; empty leaves the conversation as it is"
    default: ""
steps:
  - states: ["0", "on-their-llms"]
    label: add-fixer
    actions: [{actor: this, method: addWorker, arguments: ["fixer", 'jexl:state.getString("fixer")'], execution: direct}]

  - states: ["on-their-llms", "1"]
    label: put-each-role-on-its-llm
    note: |
      Which model judged and which model wrote is part of what this run was, so the job names them
      rather than leaving them on the conversations. An empty value leaves a conversation as it is;
      a provider this instance was not started with fails here (ChooseTheLlmPerRole_260915_oo01).
    actions:
      - actor: this
        method: setChatProvider
        arguments: ['jexl:state.getString("fixer")', 'jexl:state.getString("fixerProvider")', ""]
        execution: direct
      - actor: this
        method: setChatModel
        arguments: ['jexl:state.getString("fixer")', 'jexl:state.getString("fixerModel")']
        execution: direct
      - actor: this
        method: setChatProvider
        arguments: ['jexl:state.getString("judge")', 'jexl:state.getString("judgeProvider")', ""]
        execution: direct
      - actor: this
        method: setChatModel
        arguments: ['jexl:state.getString("judge")', 'jexl:state.getString("judgeModel")']
        execution: direct

  - states: ["1", "enter-1"]
    label: add-judge
    actions:
      - actor: this
        method: addWorker
        arguments: ["judge", 'jexl:state.getString("judge")']
        execution: direct
      - actor: this
        method: copyState
        arguments: ["text", "original"]
        execution: direct
""";

    private static final String HEAD_FILES_PARAMS = """
name: polish-files
description: |
  Works through a directory of files without anybody watching, fixing each against the checklist one
  item at a time. Where a written standard exists, the plan reads that document and hands it to the
  fixer and to the judge as the rule — the workflow carries the path, not the wording.
params:
  dir:
    label: "The directory to read"
    description: "Every file in it that ends with the suffix is worked through, in name order"
    type: text
  suffix:
    description: "What a file name must end with"
    default: ".md"
  depth:
    description: "How many directory levels to walk; 2 when one document lives per directory"
    type: int
    default: 1
  outDir:
    label: "Where to write the results"
    description: "The fixed files are written here under their own names"
    type: text
"""
            .stripTrailing();

    private static final String HEAD_FILES_JUDGE = """
  - states: ["1", "2"]
    label: add-judge
    note: |
      A file variant has no text yet: each file is read, and kept as it arrived, in take-next-file.
    actions:
      - actor: this
        method: addWorker
        arguments: ["judge", 'jexl:state.getString("judge")']
        execution: direct
"""
            .stripTrailing();

    private static final String FILE_LOOP_HEAD = """

  - states: ["2", "next"]
    label: list-files
    actions:
      - actor: this
        method: listFiles
        arguments: ['jexl:state.getString("dir")', 'jexl:state.getString("suffix")', "files", 'jexl:state.getString("depth")']
        execution: direct

  - states: ["next", "enter-1"]
    label: take-next-file
    note: |
      Takes the next file, reads it, and clears the per-criterion budgets so each file starts with
      a full allowance. Fails when the list is empty, and the transition after this one ends the job.
    actions:
      - actor: this
        method: takeNext
        arguments: ["files", "file"]
        execution: direct
      - actor: this
        method: readFile
        arguments: ['jexl:state.getString("file")', "text"]
        execution: direct
      - actor: this
        method: takeFrontMatter
        arguments: ["text", "head", "text"]
        execution: direct
      - actor: this
        method: copyState
        arguments: ["text", "original"]
        execution: direct
      - actor: this
        method: putJson
        arguments: {path: work, value: 'jexl:state.getString("outDir") + "/.work/" + state.getString("file").substring(state.getString("file").lastIndexOf("/") + 1)'}
        execution: direct
      - actor: this
        method: putJson
        arguments: {path: orig, value: 'jexl:state.getString("work") + ".orig.md"'}
        execution: direct
      - actor: this
        method: putJson
        arguments: {path: before-path, value: 'jexl:state.getString("work") + ".before.md"'}
        execution: direct
      - actor: this
        method: writeFile
        arguments: ['jexl:state.getString("orig")', "original"]
        execution: direct
""";

    private static final String WRITE_AND_SKIP = """

  - states: ["write", "next"]
    label: write-result
    actions:
      - actor: this
        method: joinFrontMatter
        arguments: ["head", "text", "whole"]
        execution: direct
      - actor: this
        method: writeFile
        arguments: ['jexl:state.getString("outDir") + "/" + state.getString("file").substring(state.getString("file").lastIndexOf("/") + 1)', "whole"]
        execution: direct
      - actor: this
        method: appendJson
        arguments: {path: done, value: 'jexl:"written: " + state.getString("file")'}
        execution: direct

  - states: ["skip", "next"]
    label: leave-the-original-alone
    note: |
      The last check could not be passed within its allowance, so nothing is written for this file
      and the job says which one it was.
    actions:
      - actor: this
        method: appendJson
        arguments: {path: done, value: 'jexl:"not written (the last check did not pass): " + state.getString("file")'}
        execution: direct
""";

    private static final String DONE_TEXT = """

  - states: ["done", "end"]
    label: done
    actions: [{actor: this, method: finish, arguments: ["text"], execution: direct}]
""";

    /**
     * Where the text variant goes when the last check cannot be passed.
     *
     * <p>It has no file to leave alone, so it answers with the text as it stands and says the
     * check did not pass — rather than dying with "no matching transition", which is what a
     * missing state gets you.</p>
     */
    private static final String SKIP_TEXT = """

  - states: ["skip", "end"]
    label: the-last-check-did-not-pass
    actions: [{actor: this, method: finish, arguments: ["text"], execution: direct}]
""";

    private static final String ALL_DONE = """

  - states: ["next", "end"]
    label: all-done
    note: Reached when no file is left.
    actions: [{actor: this, method: finish, arguments: ["done"], execution: direct}]
""";

    private static String build(boolean overFiles) {
        StringBuilder out = new StringBuilder();
        if (overFiles) {
            String head = HEAD_TEXT
                    .replace(HEAD_TEXT.substring(0, HEAD_TEXT.indexOf("  fixer:")), HEAD_FILES_PARAMS + "\n")
                    .replace(HEAD_TEXT.substring(HEAD_TEXT.indexOf("  - states: [\"1\", \"enter-1\"]"),
                             HEAD_TEXT.length() - 1), HEAD_FILES_JUDGE);
            out.append(head).append(FILE_LOOP_HEAD);
            out.append(budget("tries-verify"));
            for (int i = 1; i <= CRITERIA.size(); i++) {
                out.append(budget("tries-" + i));
            }
            out.append(ALL_DONE);
        } else {
            out.append(HEAD_TEXT);
        }
        for (int i = 1; i <= CRITERIA.size(); i++) {
            String next = i < CRITERIA.size() ? "enter-" + (i + 1) : "verify";
            out.append(block(i, CRITERIA.get(i - 1), next, overFiles));
        }
        out.append(verifyBlock(overFiles ? "write" : "done", overFiles));
        out.append(overFiles ? WRITE_AND_SKIP : DONE_TEXT + SKIP_TEXT);
        return out.toString();
    }

    /** One per-file counter, set back to zero as each file is taken. */
    private static String budget(String key) {
        return "      - actor: this\n        method: putJson\n"
                + "        arguments: {path: " + key + ", value: \"0\"}\n        execution: direct\n";
    }

    private PolishWorkflowGenerator() {}
}
