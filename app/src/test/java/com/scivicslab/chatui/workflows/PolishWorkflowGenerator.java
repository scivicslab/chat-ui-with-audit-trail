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
    private static String block(int i, Criterion c, String next) {
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
steps:
  - states: ["0", "1"]
    label: add-fixer
    actions: [{actor: this, method: addWorker, arguments: ["fixer", 'jexl:state.getString("fixer")'], execution: direct}]

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
            out.append(block(i, CRITERIA.get(i - 1), next));
        }
        out.append(verifyBlock(overFiles ? "write" : "done"));
        out.append(overFiles ? WRITE_AND_SKIP : DONE_TEXT);
        return out.toString();
    }

    /** One per-file counter, set back to zero as each file is taken. */
    private static String budget(String key) {
        return "      - actor: this\n        method: putJson\n"
                + "        arguments: {path: " + key + ", value: \"0\"}\n        execution: direct\n";
    }

    private PolishWorkflowGenerator() {}
}
