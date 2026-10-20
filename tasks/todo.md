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

## 続き: Actions タブを上下 2 段に（actor の一覧 / 選んだ actor の action）
- actor は増えていくので、名前を打つ field をやめ、上段に actor の一覧（`this` と生きている actor 全部、
  origin ごとの見出し、絞り込みの field）、下段に選んだ actor の action（第一文付き）。action を押すと説明に
  変わり、「← <actor>'s actions」で一覧に戻る。段の境はドラッグで動き高さは localStorage に残る。Clear は
  選択と絞り込みを消す。
- `GET /api/actions` は `{name, type, origin}` の行を返す。会話の行の type はその `ChatSessionIIAR`。
  `list`/`describe` は `origin` も受ける（名前が重複する quarkus-chat-ui と同じ形にするため）。
- 使い捨てポート 28042 でヘッドレス確認: 19 行、`project1/chat-01` を選ぶと 46 action、`sendPrompt` を開くと
  step が出て戻れる、絞り込み・Clear・ドラッグが効く、ブラウザのエラー無し。

---

# 相手の会話を見る・モデルを替える道具と、ask_chat の終わり方の 1 行

背景: project1/05（Gemma 4）に章の開始位置を訊いたとき、返ってきたのは道具呼び出しの文面だった。
モデルを替えて試す手段も、相手の状態を読む手段も、エージェントの道具として無かった。

- [x] `TextToolCallParser` が `<tool_call><function=…>` の形式を読む（テスト 6 件）
- [x] `ConversationControls`（モデル切替・ログ取得の狭い窓口）を作り、`ChatUiActorSystem` が実装して `ChatSession` に渡す
- [x] 道具 `get_chat_status` `list_chat_models` `set_chat_model` `get_chat_log` を作る
  - 読む 3 つは別プロジェクトの会話も可。`set_chat_model` は `set_workflow` と同じく別プロジェクトを拒否
  - `set_chat_model` は相手が選べるモデルの一覧に無い名前を拒否する（broker のモデル名が変わっていて 404 になった事故への手当て）
- [x] `ChatSession` の道具説明と `executeTool` に 4 つを登録する
- [x] `ask_chat` の結果の末尾に終わり方の 1 行を付ける（道具としての `ask_chat` だけ。`PlanRunner` などが使う `askQualified` の戻りは変えない）
- [x] 単体テスト（`mvn test`、`-DskipTests` は使わない）
- [ ] 稼働中の audit-trail への反映（ビルド・再起動）は別途確認

## Review（相手の会話を見る・モデルを替える道具）

- `app` モジュール全体 356 件が通る（新規 `TextToolCallParserTest` 6、`ChatInspectionTest` 11、`ConversationToolsTest` 9）
- 道具の実行は、実際の会話 2 つ（chat-01 が呼び、chat-02 を見る）を 1 つのアクターシステムに置いて通した
- `set_chat_model` は、相手が選べるモデルの一覧に無い名前を拒否し、一覧を答えに載せる
- `ask_chat` の末尾の 1 行は、道具としての `ask_chat` だけ。`askQualified`（`PlanRunner` など）の戻りは変えていない
- 稼働中の audit-trail（28014 など）には反映していない。ビルドして再起動するまで、この機能は使えない
- 未コミット。別の作業の変更が同じ作業ツリーにある（`ArtexProxyResource.java`、`app.js`、`application.properties`、`KeybindResource*`）

# エージェントの道具で会話を作る・消す（`create_chat` `delete_chat`）

背景: 実験用の会話（chat-06 など）を作る道具が、エージェントに無かった。REST の `GET .../status` を
存在しない id に呼ぶと会話が副作用で作られるため、実験のたびにそれを使い、後で消していた。
`get_chat_status` 道具は存在しない id に `error: chat not found` を返し、作らない。
REST には作成 `POST /{projectId}/chats`（id は actor system が採番し `{"chatId":"06"}` を返す）と
削除 `DELETE /{projectId}/chats/{chatId}` が既にある。足りないのは道具だけである。

方針（未承認）:
- [ ] `ConversationControls` に `String createChat(projectId)` と `ChatDeletion deleteChat(projectId, chatId)` を足す
      （`ChatUiActorSystem` は既に両方を持つ。窓口に出すだけ）
- [ ] `CreateChatTool`（`create_chat()`）: 呼んだ会話と同じプロジェクトに採番して作り、`ok: created project1/chat-06` を返す。
      別プロジェクトには作れない（`set_chat_model` と同じ理由）
- [ ] `DeleteChatTool`（`delete_chat(chatId)`）: `ChatUiActorSystem.deleteChat` の拒否理由
      （chat-01、最後の1つ、実行中、キュー有り、子有り）をそのまま答えに載せる。自分自身の削除は拒否する
- [ ] `ChatSession` の道具説明と `executeTool` に2つを登録する
- [ ] `ConversationToolsTest` に、作成→採番→`get_chat_status` で見える／削除→見えない／拒否の各場合を足す（`mvn test`、`-DskipTests` は使わない）
- [ ] 稼働中の audit-trail への反映（ビルド・再起動）は別途確認

決めてほしいこと:
- REST の `GET .../status` ほか約14個が `createChat` を呼ぶ「無ければ作る」は、画面（会話の切替時）が
  依存しているため、今回は変えない。変えるなら別の計画にする。

## 「無ければ作る」の廃止（追加・実装済み）

会話は `POST /api/projects/{p}/chats`（採番）、`POST .../chats/{id}`（id指定）、道具 `create_chat` でだけ作る。
名前を言及しただけの要求（`GET .../status` など12個のエンドポイント、`setModel`/`setNoThink`/`setEffort`/`setTemperature`/`setProvider`）は
存在しない会話を作らず、REST は 404 `no such conversation: ...`、アクターシステムの設定変更は `false`／`IllegalArgumentException` を返す。
- [x] `ChatUiActorSystem.hasChat` を足し、上の暗黙作成を外した（復元・`init`・採番作成は従来どおり）
- [x] `ChatResource.requireChat`: 12個のエンドポイントと provider 切替の前で 404
- [x] `app.js`: 保存済みの会話 id が 404 なら `project1/chat-01` に戻す（旧コメントが書いていた「古い id で空の会話が作られる」不具合の根本対処）
- [x] `ChatIsMadeOnlyWhenAskedTest`（3件）。`setNoThink` に旧動作を戻すと落ちることを確認（赤→緑）
- 全体 391 件。`ArtexProxyLoggingTest` は変更前の `HEAD` でも 3 回中 2 回落ちる既存の不安定なテスト（非同期のログ書き込みを5秒待つ）で、今回の変更とは無関係
- [ ] 稼働中の audit-trail への反映（ビルド・再起動）は別途確認。反映までは旧動作のまま

# request messagesに送ったプロンプトを省略せず残す（取り下げ：下の「エージェントループがプロンプトを決める」に取り込む）

現状: 1 ターンの間、`OpenAiCompatProvider.history` にはそのターンで送った全メッセージ
（先頭は `ChatSession.firstStepPrompt()` の前置き約 1.4 万字 + 利用者の文、続いて各ステップの
モデル応答とツール結果の観察）が積まれる。ターンが終わると `collapseTurn(question, answer)` が
それらを捨て、利用者の文だけ（前置きなし）と最終回答の 2 件に置き換える
（`TurnResourceLimits_260830_oo01`：ステップの観察が前のターンを押し出すため）。
そのため `get_request_messages` / `GET .../request-messages` が見せるrequest messagesは、実際に送った文と一致しない。

要求: request messagesには、やり取りしたプロンプトを省略せずに残す。

方針（案）:
- [ ] `ChatSession` のターン終了時の `collapseTurn` 呼び出しをやめ、ターン中に積んだメッセージ（前置きつきの先頭プロンプト、
      モデルの各応答、ツール結果）をそのままrequest messagesに残す
- [ ] 予算を超えたら古いメッセージから落とす既存の `fitHistoryToBudget` はそのまま。落とすのは 1 メッセージずつではなく
      ターン単位にするか（途中から始まるrequest messagesは user/assistant の対応が崩れる）を決める
- [ ] 再起動後の復元（`ChatUiActorSystem` の `collapseTurn(t.question(), t.answer())`）は、I/O ログの `userPrompt`
      （前置きつきの全文）から作り直す。ステップごとの observation はログにあるので同じ形に戻せるか確かめる
- [ ] テスト: 2 ターン送ったあとのrequest messagesが、実際に送った messages と一致する（前置き含む）／予算超過時の落とし方／復元
- [ ] `RequestMessagesControl_261018_oo01` の説明（「次に送る配列」）と合うか確認

決めてほしいこと:
- 前置き 1.4 万字がターンごとにrequest messagesへ入る（10 ターンで約 14 万字）。そのまま残すのが要求どおりだが、
  前置きを会話の先頭に 1 回だけ置く形（別の変更）にしてよいか。

# ツールなしの会話（`ToolSet.NONE`）（取り下げ：下の「エージェントループがプロンプトを決める」に取り込む）

背景: openai-compat の会話は、ツールを使わなくても毎ターン約 1.4 万字の前置き
（書式の説明 786 + ツール説明 9,874 + 決まり 975 + スキル一覧 2,299）が先頭プロンプトに付く。
文章生成の会話（フレームの生成）では要らず、モデルの答えを変える
（概念リストが 5 個→1 個、`load_skill` の呼び出しを書く）。ツールを選ぶ手段が `full` / `harness` の 2 つしかない。

方針（最小）:
- [ ] `ToolSet`（plugin-api）に `NONE` を足す。いまは除外する名前の一覧で作られていて、
      プラグインのツールは名前が決まっていないため、`contains` を「すべて含まない」と書ける形にする（`parse("none")` も通す）
- [ ] `ChatSession.firstStepPrompt()` で `NONE` のとき、書式の説明・ツール説明・決まり・`read`/`write` の許可先・スキル一覧を付けない。
      プロジェクトの指示（`AGENTS.md` / `CLAUDE.md`）は利用者が置いたものなので残す
- [ ] `executeTool` の既存の拒否（`toolSet.contains` が偽なら `error: tool ... is not available`）がそのまま効くことを確かめる
- [ ] 提供元の一覧に `Local LLM (no tools)`（openai-compat + `NONE`）を足す。画面の選択肢は `kind:toolSet` 形式で既に対応
- [ ] 設定の記録（`tools` は文字列）と復元（`ToolSet.parse`）が `none` を通す
- [ ] テスト: 先頭プロンプトが利用者の文だけになる／ツール呼び出しを書かれても実行しない／`parse` と記録・復元／
      既存 `ChatSessionToolSetTest` が通る。E2E `ProviderSelectE2E` の選択肢の数の前提を確認
- [ ] 別の変更として: 用途別（ファイル、文書・Web、会話操作）の組、各ツール説明の書式の重複（13 文・815 字）の削除、
      `create_chat` が提供元とツールを指定できること

# エージェントループがプロンプトを決める（方針は承認済み、実装は未承認）

背景: 会話に送ると、利用者の文の前に約 1.4 万字の固定の文（ツール説明など）が毎回付く。ワークフローを選んでも、
`ChatSession.firstStepPrompt()`（Java）が付けるので消せない。ワークフローが組み立てるのは文字列で、
モデルへ渡す `messages`（JSON）やrequest messagesは、ワークフローから決められない。ツールなしの会話や、
request messagesに送った文を残す会話を作る手段がない。

方針（承認済み）:
- エージェントループのワークフロー（YAML）が、プロンプトの組み立て・request messagesに残す内容を含めて、会話の動かし方をすべて決める。
  用途ごとにループを用意し、呼び出すときにどれを選ぶかで動作を選ぶ。
- 道具の実行の許可（`ToolSet`）はいまのまま、プロンプトとは別に残す。モデルが知らないツールを呼んでも、実行は断るだけ。

作業:
- [x] 1. 前置きを部品に分け、ワークフローの操作として足す（d9f2e2a。部品は `ChatSession` の操作 `toolInstructions`/`skillCatalog`/`projectInstructions`/`turnText`。
      PromptBuilder 自身はツールを知らないため、操作は会話側に置いた。文章生成用 `prompt-construction-plain.yaml` を追加）。以下は元の記述:: ツール説明（会話の `ToolSet` の分だけ）、ツールの決まり、
      スキル一覧、プロジェクトの指示（`AGENTS.md`/`CLAUDE.md`）。`firstStepPrompt()` はこれらを積む既定の手順に置き換える（出力は変えない）
- [ ] 2. ワークフローが `messages`（JSON）をそのまま渡せるようにする: `PromptBuilder.buildMessages(request messages)` の結果を会話が受け取り、
      提供元が文字列の代わりに配列を送れるようにする（`sendPrompt` に並ぶ入口）
- [x] 3. エージェントループの YAML が、使うプロンプト構築のサブワークフローを自分で指定する（最上位の `promptWorkflow: <file>`。
      ループを読み込むときに設定し、書かないループは既定に戻す。存在しないファイルを書いたループは断り、前のループに戻す。
      `plain-text-loop.yaml` を追加。`doc-retrieval-loop.yaml` には書かない：ベンチマークの型 3 はループだけを替えて測るため）。以下は元の記述:（`constructPrompt(ファイル名)` のような操作）。
      別に選ぶ `POST .../prompt-workflow` は、ループの指定が無いときの既定として残す
- [ ] 4. request messagesに残す内容もループが決める: 既定は今のまま（ターン終了時に利用者の文と最終回答だけにする）。
      送った文をそのまま残すループを作れるようにする
- [ ] 5. 用途別のループを作る: ① 既定（いまと同じ）② 文章生成用（利用者の文だけ、ツール説明なし）
      ③ 文書検索（`doc-retrieval-loop`）
- [ ] 6. テスト: 既定のループが送る文が変更前と一致する／文章生成用ループが送る messages が利用者の文だけである／
      ループがrequest messagesを残す内容を変えられる／`ToolSet` の拒否が効く。`mvn test` を回す
- [x] 7. 実機: 28010 に反映して `chat-06` で確かめた。既定は前置き 13,966 字（変更前と同じ）、plain は 32 字（利用者の文だけ）、plain の会話の概念リストは 5 個（以前は 1 個）。
      `ONLY` に「各文は句点（。）で終える」を足すと、22 件すべてが句点で終わる（足す前は 1/22）。概念リストにも句点が付くので、リストの質問は別の指示にする
- [ ] 8. 設計文書を doc_SCIVICS003 に書く（`ProjectProperty`・`RequestMessagesControl` と同じ置き場）

順序: 1 → 2 → 3 の順に、各段階でテストを通し、段階ごとにコミットする（push は毎回確認）。

# 残りの作業（全部やる。2026-10-20 承認）

順序は、小さくて土台になるものから。各項目をテストして、項目ごとにコミットする（push は区切りで確認）。

- [x] A. 選べるエージェントループの一覧（`AgentLoopCatalog`、Agent Loop タブに「Agent loop:」の選択肢。ブラウザでの動作確認は w206-e2e-test のシナリオで別に足す）: `GET .../chats/{c}/agent-loops`（名前・題・説明・指定しているプロンプト構築・今のループか）。
      選ぶ操作は既存の `POST .../agent-loop-workflow` のまま（ベンチマークが使うため名前を変えない）。
      `.../workflows` は「この会話が今使っているワークフローを読む画面」専用で、名前はそのまま。画面（Agent Loop タブ）に選択肢を足す
- [x] B. 引く操作が作らない（`IoLogStore.sessionIdOf`/`jobSessionIdOf` を足し、引くだけのテストを替えた。`ensure*` は「開く」書き込みとして残す。`hasChat` は登録簿に問い合わせる）。元の記述: `IoLogStore.ensureSession`/`ensureJobSession` を、引くもの（作らない）と作るものに分ける。
      `ChatUiActorSystem.hasChat` は、アプリ側の `chats` ではなく `ActorSystem` の登録簿に問い合わせる
- [x] C. （plugins 7deb3fa を 4.5.0 として Maven Central に公開し、chat-ui の依存を 4.5.0 に上げた（660263d））`H2LogStore` を素の同期 POJO にし（plugins）、`chat-ui-with-audit-trail` の `IoLogStore` が `ActorRef` で包んで `ask(...).join()` で呼ぶ。
      手書きのスレッド・キュー・ラッチを無くす。`quarkus-chat-ui`・`quarkus-chat-ui3` は固定した版（4.2.0 系）のまま動き、版を上げたときに同期書き込みになる
- [x] D. ワークフローが組んだ `messages`（JSON）を会話が受け取り提供元が送る。方針は案1（ターンのために組んだ system メッセージはそのリクエストだけのもので残らず、残るのは質問と答えだけ）。過去のターンを載せない `plain-text-no-context-loop` と載せる版を用意した。`RequestMessagesPerTurnTest` で確認済み。ワークフローの `result` が JSON に見える文字列だと空になる欠陥が POJO-actor にあり、直した（POJO-actor b98bf13、4.5.0 以降で必要）
- [x] E. （コード・テストは完了：`frame/`（`RuleFile`・`FrameFiller`）、`PlanRunner` の操作 `frameNext`/`framePrompt`/`frameFill`/`frameExpand`/`setChatAgentLoop`、`frame-fill-loop.yaml`、`FrameFillLoopTest`。残り：実機で Qwen により通しで動かし、`rst:verify` で検査する）本題: フレームから文章を生成する Turing Workflow（`rst-agent-loop.yaml` に `:frame`/`:sample` の扱いを足す）。 実機（28010、qwen3.8-flash-next-tensorfold、no-think、project1/chat-07）で通した: job-02 が 19 秒で 3 概念 × 4 空欄 + 1 を埋め、`rst:verify` は 0 violations。入力と結果は `~/works/tmp/frame-live-01/`。内容の誤り（Math.sqrt を二乗の手段に挙げる等）は残る
      ブランクごとに 1 回の呼び出し、request messagesつき、`plain-text-loop` 上で動かす
- [x] F. 文書: 設計文書 `AgentLoopDecidesThePrompt_261020_oo01` を doc_SCIVICS003 の `040_design/030_...` に書いた

# 用語（「履歴」は使わない）

- **request messages**: 次にモデルへ送る `system` / `user` / `assistant` の発言の並び。提供元の `history` で、`get_request_messages` が読み、`set_request_messages` が書く。
- **画面の会話記録**: 画面に出す発言の一覧（`ChatSession.conversationHistory`）。モデルへは送らない。
- **入出力ログ**: `IoLogStore` が H2 に書く記録。ターンごと・ステップごとに、送った文と受けた文が入る。モデルへは送らない。
以下の「request messages」は、1 つ目のことである。

# 本からサンプル集とフレームを作る（FramesAndSamplesFromBooks_261021_oo01）

順序: 規則ファイルを扱う処理をプラグインに移す → 本の規則ファイル化 → お手本の引き方 → フレーム作成。各段でテストしてコミットする。

- [ ] G1. `plugin-rst-frame` を Turing-workflow-plugins に作る（4.6.0-SNAPSHOT）。`RuleFile`・`FrameFiller` とそのテストを chat-ui から移し、
      規則ファイルを読み書きする操作をこのプラグインのアクターの操作にする。会話に質問を送る操作（`askWorker` など）は chat-ui の `PlanRunner` に残す。
      `fill-frame.yaml` は、このアクターを作って使う形に書き換え、`FillFrameTest` が通ることで確かめる
- [ ] G2. 本を読む部分（素の POJO）: ページ番号と柱を除く、行末で切れた文をつなぐ、ファイルの境目で切れた段落をつなぐ、見出しを節にする、
      コード・数式のまとまりを分ける。Core Java 3 章 p001-010 とアルゴリズムとデータ構造 p101-110 を入力にしたテストで確かめる
- [ ] G3. 読み違いの直しの採否（文字の単位で比べ、変わった割合で決める）と、関係の答え（文の番号と関係の名前）から規則を作る部分。テストで確かめる
- [ ] G4. `rules-from-book.yaml`（ジョブのワークフロー）。偽の書き手で最後まで通るテスト
- [ ] G5. 実機: Core Java の 3.1 節を `rst-on-lisp/samples/language-feature.lisp` にし、手で作った `example.lisp` と関係の付け方を比べる
- [ ] G6. お手本の引き方（`:sample-role`、パラメータ `samples`）を `fill-frame.yaml` に足す
- [ ] G7. `frame-from-rules.yaml`

確かめること（G1 の前）: Build Snapshot が plugin-rst-frame の SNAPSHOT を取れるか（取れなければ 4.6.0 のリリースが要る）
