# audit trail の使い方チュートリアル（5 段）

置き場所: `doc_SCIVICS003/docs/chat-ui-with-audit-trail/050_tutorials/`（`010_ThreeStartupConfigurations_260912_oo01` の次）

狙い: graph engineering をユーザーの視点で段階的に説明する。1 段につき増えるものは 1 つだけにする。

| 番号 | 題 | 増えるもの |
|---|---|---|
| 020 | 会話を 1 つ回し、その記録を読む | — |
| 030 | 会話を 2 つにして、片方がもう片方に頼む | 2 つ目の会話 |
| 040 | 監督役と作業役に分ける | 役割の固定 |
| 050 | 計画を書いて複数の worker へ並列に配る | 並列 |
| 060 | 出来た graph をプロジェクトのジョブとして回す | 再現性 |

実機確認: 稼働中の 28014 には触れない。使い捨てのポートで Local LLM 構成（`chat-ui.servers` = gpu-broker 28005）を立てて確かめる。provider 切替の段だけ構成 3 を使う。

## 手順

- [x] 1. 使い捨てインスタンスを立て（28032、構成 2）、020 の手順を実際に回して記録を採る
- [x] 2. 020 を書く（`020_OneConversationAndItsRecord_260913_oo01`）
- [x] 3. 030 を回して書く（構成 3 での provider 切替を含む）
- [x] 4. 040 を回して書く
- [x] 5. 050 を回して書く
- [x] 6. 060 を回して書く（Run タブと Batch Jobs、JobParameterForm_260913_oo01 の続き）
- [ ] 7. コミット・push（push は確認を取る）

このあとの予定（別タスク）: 一般論ではなく具体的応用。文章をブラッシュアップさせるワークフロー、文献を調査して詳細なレポートを作り上げるワークフロー。

## Review

5 本とも、使い捨てのポート（28032＝構成 2、28033＝構成 3）で実際に回してから書いた。稼働中の 28014 には触れていない。

実機で分かったこと:
- `ask_chat` などの `chatId` に渡すのは `02`。`chat-02` と書くと `project1/chat-chat-02` になって見つからない
- 循環は待たずに断られる（`error: refused — asking project1/chat-01 would create a circular wait`）。2 本の循環を実機で踏んだのは今回が初めて
- 監督役として走ったターンは Sessions タブから読めない。`lastTurn` は進むのに `GET /api/sessions/{id}/trace/{turn}` が `no such turn` を返す（040 に記載、要修正）
- `turing-workflow` 4.1.0 では値の読み出しは `jexl:state.getString('key')`

次（別タスク）: 具体的応用。文章をブラッシュアップさせるワークフロー、文献を調査して詳細なレポートを作り上げるワークフロー。

---

# 右ペインに Actions タブ（chat と project の両パースペクティブ）

## 経緯
quarkus-chat-ui の Workflow タブと Actions タブを移す話から始まり、Workflow タブは要らないと決めた。
quarkus-chat-ui では workflow の実行器（harness）が会話の provider に直接ターンを送るので workflow は
会話の 1 ターンの代わりになり、プロンプトキューの項目として並ぶ意味があった。CUAT では workflow は別の actor
（`PlanRunner`、project の `job-NN` か会話の `.plan`）が実行し、会話には `sendPrompt` でプロンプトを入れる側
なので、キューの項目にはならない。実行の入口は project パースペクティブの Run が既にある。

## やったこと
- [x] 親 pom: turing-workflow 4.3.0-SNAPSHOT。各モジュールの pom が 4.2.0 を直書きしていたのを親のプロパティに
- [x] app/pom.xml: doclet 実行（`META-INF/turing-plugin/<groupId>.<artifactId>.json` と services の索引）。
      JSON Schema 生成器に module のパスを渡す（4 モジュール化以来 reactor root の空の target/classes を見て
      schema を 1 つも書いていなかった）
- [x] `PlanRunnerIIAR` の 26 step と `PlanWorkerIIAR.ask` を `@Action` メソッドに。Javadoc は第一文・本文・
      同梱 YAML から取った `<pre>` の step・`@param args` の位置
- [x] `ChatUiActorSystem.actorClassOf`（`this` → `PlanRunnerIIAR`、会話の修飾名 → その `ChatSessionIIAR`、
      それ以外は生きている actor のクラス）と `workflowActorNames`
- [x] `ActionsResource`: `GET /api/actions`、`/api/actions/list?actor=`、`/api/actions/describe?actor=&action=`
      （actor 名に `/` が入るのでクエリ）
- [x] console.html / console.js / console.css: Actions タブ。ボタンは perspective ごとに 1 つずつ、section は
      perspective 無しで両方に出る。Show・Clear・一覧に第一文・1 件表示に引数・本文・YAML
- [x] `ActionDescriptionTest`（4 件）。259 テスト緑。使い捨てポート 28042 で両パースペクティブをヘッドレス確認

## Review
- `this` は 43 action（plan の 26 と engine の 17）が全部説明付き。`project1/chat-01` は 46 action のうち
  27 に説明がある: `ChatSessionIIAR` の 29 のうち Javadoc に第一文が無いものは名前と組み立てた step だけ出る。
  書くなら `ChatSessionIIAR` の Javadoc を書けば次のビルドで出る。
- actor の候補は `this` と actor 木の全名（19 件、`promptBuilder` などの内部 actor も含む）。
- 稼働中の 28014・28030・28031 と `~/works` のリンクは触っていない。
