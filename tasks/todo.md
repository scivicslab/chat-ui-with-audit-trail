# ChatSession 実装 — ステージ1（単発応答）・ステージ2（agent loop）

設計文書: `doc_SCIVICS003/docs/chat-ui-with-audit-trail/030_development/010_skeleton/` の
`040_ChatSessionPorting_260823_oo01`・`110_ChatSessionIIAR_260810_oo01` に基づく。

## 計画

- [x] 依存クラス（`LlmProvider`・`ProviderContext`・`ProviderCapabilities`・`ChatEvent`・`AuthMode`）を無改名で移植
- [x] `OpenAiCompatProvider`・`OpenAiCompatClient`・`ChatMessage`・`ToolDefinition`・`ContextLengthExceededException`を無改名で移植
- [x] `IoLogStore`・`IoLogView`を無改名で移植（`plugin-log-db`依存を追加。`ChatSession`のコンストラクタ引数型として必要だったため、見送り予定から前倒し）
- [x] `PromptQueue`を移植（`ChatSession.getPromptQueue()`のコンパイル依存として先出し）
- [x] `ChatSession`（`Interpreter`のサブクラス）を新規作成
- [x] `ChatSessionIIAR`（`InterpreterIIAR`のサブクラス）を新規作成
- [x] `ChatUiActorSystem.createTab`への配線（`ChatSessionIIAR`・`provider`子・`PromptQueue`）＋`getActorTree()`の`iiActors`対応
- [x] `mvn install`（`rm -rf target`後、`-DskipTests`無し）でビルド確認 — 成功、既存の`ChatUiActorSystemActorTreeTest`も緑
- [x] `sendPrompt`→単発応答→`getResult`の実機確認（`192.168.5.16:8000`のvLLM、`google/gemma-4-26B-A4B-it`）— 成功

`McpRequestQueue`（インスタンス間メッセージ用、agent loopのツール実行には使わない）・`StallMonitor`はステージ2の対象外のまま。

## 計画（ステージ2）

設計文書: `040_ChatSessionPorting_260823_oo01`（2-b-i/ii/iii）・`100_ChatSessionAgentLoop_260823_oo01`
（agent loop本体＋ツール本体の移植）・`080_McpRequestQueuePorting_260823_oo01`（`McpRequestQueue`は
使わない、の記録）に基づく。

- [x] `quarkus-chat-ui3`のツール実装6個相当を`com.scivicslab.chatui.agent`へ移植
      （`FileReadTool`・`FileWriteTool`・`WebSearchTool`・`FetchTool`・`DocSearchTool`・`ContextBudget`。
      `calc`は移植せず`Turing-workflow`既存の`JShellCalculator`を利用）
- [x] `DocSearchTool`の修正移植（既定URL`28005`→`28001`、死んでいる`/api/keyword-map`呼び出しを削除）
- [x] `ToolCall`レコード＋`TextToolCallParser`を移植（`VllmResponse.ToolCall`依存を排除）
- [x] `chat-session-agent-loop.yaml`（`(think→act)*→end`の状態遷移）を新規作成
- [x] `ChatSession`に`start`/`stepExpectingAction`/`runTool`/`finish`とターン単位のフィールドを追加
- [x] `ChatSessionIIAR`のコンストラクタでYAMLロード＋`callByActionName`に3アクションを追加
- [x] `PromptQueue.dequeueAndSend`を`chat.startPrompt(...)`から`chat.start(...)`+`chat.runUntilEnd()`へ変更
- [x] `mvn install`（`rm -rf target`後）でビルド確認 — 成功、既存テストも緑
- [x] agent loopの実機確認（`192.168.5.16:8000`のvLLM）——「calcツールを使って23×47を計算して」という
      プロンプトに対し、LLMが`<invoke name="calc">`でツール呼び出しを要求→`runTool`が`JShellCalculator`で
      実行（結果1081）→次の`stepExpectingAction`でLLMが最終回答「1081」を返す、という一往復が実際に動作した

## レビュー

ステージ1は完了。`sendPrompt`→`PromptQueue`（busy中は保留）→`ChatSession.startPrompt`→`OpenAiCompatProvider`経由の実LLM呼び出し→`storeCompletedResult`→`getResult`という経路が、実際のvLLMサーバに対して動作することを確認した。

実装中に見つかった、設計文書に無かった/食い違っていた点：

1. **`turing-workflow:3.7.0`が公開するpomに`commons-jexl3`の依存が抜けていた**——`Interpreter`（`ChatSession`が継承）が実際に使うのに、ライブラリ側のpomに宣言が無い。`chat-ui-with-audit-trail`のpomに明示的に追加して回避した。
2. **`ChatSessionIIAR`から`ActorRef<ChatSession>`が要る箇所で`this`をそのまま渡せない**——`ChatSessionIIAR extends InterpreterIIAR extends IIActorRef<Interpreter>`なので、`this`の静的型は`ActorRef<Interpreter>`であり、Javaのジェネリクスは不変（invariant）なので`ActorRef<ChatSession>`への自動変換は起きない。`110_ChatSessionIIAR_260810_oo01`の説明（「`this`をそのまま渡せる」）はこの点で誤りだった——`(ActorRef<ChatSession>) (ActorRef<?>) this`という明示的なunchecked castが要る。文書側の追随修正が必要。
3. **`IoLogStore`は結局ステージ1で移植することになった**——`ChatSession`のコンストラクタ引数型として必要で、見送るとコンパイルが通らないため。

上記2の`110_ChatSessionIIAR_260810_oo01`の誤りは、ステージ2着手前に文書側を修正済み（`self()`ヘルパーの記述に置き換え、commit `7e7053e`）。

### ステージ2

ステージ2も完了。`sendPrompt`→`PromptQueue`→`chat.start(...)`+`chat.runUntilEnd()`→
`chat-session-agent-loop.yaml`の状態機械→`stepExpectingAction`（LLM呼び出し・`<invoke>`検出）→
`runTool`（`read`/`calc`/`web_search`/`fetch`/`search_docs`/`write`のいずれかを実行）→`finish`
という経路が、実際のvLLMサーバに対してツール呼び出しを1往復させて動作することを確認した。

設計時の検討では見えず、実装・実機テストで初めて見つかった問題：

1. **`InterpreterIIAR`のコンストラクタは`selfActorRef`を自動設定しない**——`Interpreter.setSelfActorRef(IIActorRef<?>)`という別メソッドが存在し、`IIActorRef`実装側が明示的に呼ぶ設計になっている。呼び忘れると、YAMLの`actor: this`が`Interpreter.action()`内で`selfActorRef==null`の分岐（`system.getIIActor("this")`という額面通りの文字列検索）に落ち、`"Actor not found: this"`で毎回失敗する——実機テストで最初に踏んだ。`ChatSessionIIAR`のコンストラクタに`chatSession().setSelfActorRef(this)`を追加して解決。
2. **YAMLの`execution`は既定が`POOL`**——`Interpreter.action()`は、アクションの実行モードを明示しない限り`ManagedThreadPool`へディスパッチする（`ExecutionMode.POOL`が既定値）。これは`chat-session-agent-loop.yaml`の設計時に検討した「`runUntilEnd()`は`tell()`クロージャの中でプレーンなJavaメソッドとして呼び、`ManagedThreadPool`を経由させない」という前提を、`execCode()`内部の個々のステップ実行の段階で覆してしまう——`ChatSession`自身のフィールド変更が、呼び出し元のアクタースレッドとは別の`ManagedThreadPool`のスレッドで起きてしまい、`pollAutonomousActivity`等の通常の`tell`経由の処理と競合しうる。YAMLの各ステップに明示的に`execution: direct`を指定することで、`actorAR.callByActionName(...)`が呼び出し元のスレッド上で直接実行されるようにして回避した。
3. **`transitionTo`と`setCurrentState`は別物**——`setCurrentState(state)`は`currentState`だけを書き換え、遷移スキャンの起点（`currentTransitionIndex`）を動かさない。`start()`が`setCurrentState("think")`を呼ぶと、2回目以降のターンで`currentTransitionIndex`が前回終了時の位置（`think→end`側）に残ったままになり、次の`execCode()`が`think→act`を飛ばしていきなり`think→end`（`finish`）を実行してしまう——1ターン目だけ正しく動いて2ターン目以降が壊れる、という発見しにくい不具合になるところだった。`transitionTo("think")`（`setCurrentState`＋`findNextMatchingTransition`を両方行う）を使うことで回避した。**同一`ChatSession`で2ターン連続実行する実機テストで確認済み**（「日本の首都は」→「東京です。」の後、同じインスタンスで「フランスの首都は」→「パリです。」が正しく完了した）。
4. **`McpRequestQueue`ベースのagent loop設計は誤りだった**——`080_McpRequestQueuePorting_260823_oo01`に記録済みの通り、`runTool()`は`McpRequestQueue`を使わず、`quarkus-chat-ui3`から移植したインプロセスのツール実装を直接呼ぶ。

`read`ツールも実機確認済み——モデルが知り得ない合言葉を書いたファイルを用意し、「readツールで読んで合言葉を答えて」と指示したところ、実際に`FileReadTool.read`経由でファイル内容を取得し、正しい合言葉を最終回答に含めた。

5. **`write`のような複数パラメータのツールで、モデルが`<parameter name="...">`書式を守らないことがある**——実機テストで最初、モデルが`write`を呼ぶ際に`<path>write-marker.txt</path>`・`<content>フラミンゴ</content>`という、パラメータ名をタグ名にした省略形を使い、`TextToolCallParser`の`PARAM`正規表現（`<parameter name="...">`しか見ない）が何も拾えず、`path required`エラーを繰り返して`MAX_STEPS`（6）に達し失敗した。システムプロンプトに「1パラメータの例」と「2パラメータ（`write`）の例」を具体的に書き、`<path>`のような省略タグを明示的に禁止する一文を足したところ、モデルは正しい`<parameter name="path">`／`<parameter name="content">`形式を使うようになった——`write-marker.txt`に「フラミンゴ」という指定した文字列が実際に書き込まれることを確認した。ここではプロンプト側の修正で解決したが、`TextToolCallParser`自体を省略タグにも寛容にする、という代替案は採らなかった（`quarkus-chat-ui3`からの無改名移植を優先）。

`fetch`・`web_search`・`search_docs`も確認した。ツール実装自体（`FetchTool`/`WebSearchTool`/`DocSearchTool`）はプレーンなJavaメソッドとして直接呼び出し、実際にhtml-saurus（`localhost:28001`）・DuckDuckGoから正しい結果が返ることを確認した。`fetch`はagent loop経由でも確認済み——「`http://localhost:28001/api/search?q=Interpreter...`をfetchツールで取得して」という指示で、モデルが正しいURLで`fetch`を呼び、html-saurusの実際のJSON応答が観測できた。`web_search`・`search_docs`はツール実装の直接呼び出しでは確認したが、agent loop（LLM経由）でのラウンドトリップはまだ試していない——`calc`/`read`/`write`/`fetch`で同じ`<invoke>`書式・同じ`runTool`分岐が既に4回確認できているため、リスクは低いと考えるが、実行はしていない。

`cancel()`によるagent loop途中終了も実機確認した。LLMが応答をストリーミング中（`<reason>`タグの途中）に`cancel()`を呼んだところ、`OpenAiCompatProvider`側の`sendingThread.interrupt()`が効いて即座に`"error: Request cancelled"`イベントが発生し、`runUntilEnd()`はツール実行（`calc`）まで進むことなく1.5秒程度で終了した。`busy`は`false`に戻り、`getResultStatus(resultKey)`は`"completed"`にならず`"unknown"`のまま——`finish()`の`cancelled`分岐（`storeCompletedResult`を呼ばない静かな終了）が正しく機能していることを確認した。

未検証・未対応のまま残っている点：

- `search_docs`はagent loop（LLM経由）でのラウンドトリップ未確認（ツール単体は確認済み）。`web_search`はステージ2.5（下記）のブラウザ実機テストで確認できた。
- `060_PromptQueuePorting_260823_oo01`にあった`noThink`引数は、`PromptQueue.dequeueAndSend`の新しい呼び出し（`chat.start(...)`）に渡していない（`start`のシグネチャに無い）——Qwen3以外のモデルでは今のところ影響しないが、明示的な欠落として記録する。

## 計画（ステージ2.5 — ブラウザUIとI/Oログ可視化）

「ブラウザから実際にagent loopを試し、LLMに投げた内容（システムプロンプト込み）と返ってきた内容をSessionsタブで追えるようにする」という実機end-to-endテストのために追加した一式。`quarkus-chat-ui3`の該当実装（チャット送受信のSSE機構、Sessionsタブの trace ビュー）を土台にした。

- [x] `ChatSession`のagent loopに`IoLogView.trace()`が期待するマーカー書式（`turnN/stepM/llm|tool`ラベル、`REQUEST:`/`RESPONSE:`/`TOOL_CALLS:`/`USAGE:`・`TOOL:`/`INPUT:`/`OBSERVATION:`）でのI/Oログ記録を追加（`stepExpectingAction`・`runTool`）
- [x] `SseConnection`を新規実装——`050_SseConnectionPorting_260823_oo01`が想定した生Vert.x方式ではなく、`quarkus-chat-ui3`の`Multi<String>`/`UnicastProcessor`方式（このプロジェクトはJAX-RSのみのため）
- [x] `ChatUiActorSystem`に`getPromptQueue(tabId)`/`getSseConnection(tabId)`を追加、`createTab`で`SseConnection`を配線
- [x] `ChatResource`を新規実装（`POST /api/tabs/{tabId}/chat`・`GET /api/tabs/{tabId}/chat/stream`・`GET /api/tabs/{tabId}/conversation`・`GET /api/tabs/{tabId}/models`）
- [x] `SessionsResource`を新規実装（`GET /api/sessions`・`GET /api/sessions/{id}/trace`・`GET /api/sessions/{id}/entry/{logId}`・削除系2本）——`IoLogView`は既に移植済みなので薄いラッパーのみ
- [x] `app.js`を新規作成（チャット送受信のSSE配線。タブIDは最初のテストでは`"alpha"`固定、タブ切替UIはまだ無い）
- [x] `console.js`にSessionsタブの表示ロジックを追加（`quarkus-chat-ui3`の該当コードをほぼそのまま移植——CSS（`console.css`）が既に同じクラス名で用意されていたため）
- [x] ブラウザ実機テスト（Playwright、`~/tools/headless-verify`）——「今日の天気予報を教えてください。web_searchツールを使って調べてから答えてください。」で、実際にweb_search経由の回答が返り、Sessionsタブでそのターンを展開すると`loop → LLM`のリクエストにシステムプロンプト全文を含むJSONがそのまま見えることを確認した

### 発見した不具合と修正（ユーザーの実機操作で発覚）

ユーザー自身がブラウザから「東京の週間天気予報を、表形式にして表示して」と試したところ、(1) 同じ回答が2回表示される、(2) markdownがHTMLとして整形されずそのまま表示され改行も消える、という2つの不具合が見つかった。

1. **回答の重複表示**——`ChatSession.stepExpectingAction()`は、LLMが送ってくる生のストリーミングトークンを`OpenAiCompatProvider`からそのまま`"delta"`イベントとして`turnEmitter`へ転送していた。一方`finish()`も、確定した最終回答全文を`ChatEvent.delta(answer)`として送っていた——ツール呼び出しの無い最終ステップでは、同じ文章が「ストリーミングで少しずつ」＋「`finish()`でまとめてもう一度」の二重に表示されていた。`quarkus-chat-ui3`の`AgentActor`を見直すと、ライブストリーミングは`ChatEvent.thinking(fragment)`（トレース欄）で送り、実際の回答チャンネル（`delta`）は`finish()`が1回だけ使う、という設計だった——`chat-ui-with-audit-trail`はこの区別をしていなかった。`stepExpectingAction()`内で、プロバイダの`"delta"`イベントを`"thinking"`として転送し直し、プロバイダ自身の`"result"`イベントは握りつぶす（agent loop全体の完了は`finish()`の`"result"`だけが伝える）よう修正した。
2. **markdownがHTMLとして整形されない**——`app.js`が`textContent`だけで描画しており、markdownパーサを使っていなかった。`quarkus-chat-ui3`と同じ`marked`（CDN経由）を`console.html`に追加し、assistantメッセージは`marked.parse(...)`の結果を`innerHTML`に設定するよう変更した。1で「`delta`は`finish()`から1回だけ」という設計に直したことで、ストリーミング中の未確定markdown（閉じていないフェンス等）を気にする必要も無くなった（`quarkus-chat-ui3`の`closeOpenMarkdown`相当の処理は不要）。
3. 修正後、ブラウザ実機で再確認——「東京の週間天気予報を、web_searchツールで調べてから表形式（markdownのtable）で表示してください」に対し、`<table><thead><tbody>`という実際のHTML構造で7日分の予報が描画され、1ターンにつき吹き出しは1個だけになることを確認した。

## 計画（ステージ2.6 — UIの3つの不具合報告への対応）

ユーザーが実機で発見した3件：Sessionsのrefreshボタンが効かない／Queueが表示されない／Themeが切り替わらない。

- [x] **Theme切り替え**——`#theme-select`が未配線だった。`quarkus-chat-ui3`の`app.js`と同じ、`document.documentElement`への`data-theme`属性設定＋`localStorage`永続化を追加。CSS（`styles.css`の`[data-theme="..."]`ブロック群）は既に用意されていたので配線するだけで直った。ブラウザ実機で確認済み（切替後・リロード後とも反映）。
- [x] **Queue表示**——`chat-ui-with-audit-trail`は`quarkus-chat-ui3`と違いサーバ側（`PromptQueue`）でキューイングする設計なので、クライアント側の下書きキューではなく、サーバのキュー深さを表示する形にした。`GET /api/tabs/{tabId}/queue`を新設し、`app.js`が送信直後と`busy`中2秒おきにポーリングして`#queue-area`に反映する。実装中に見つけた本当のバグ：`sendPrompt()`が`if (!text || busy) return;`というガードを持っており、`busy`中に2件目を送信しようとするとJS側で黙って握りつぶされ、サーバへ届くことすら無かった（`#send-btn`の`disabled`属性もbusy中は無効化されており、二重に阻止していた）。`PromptQueue`はまさに「busy中に届いたプロンプトを保留する」ために存在するので、この2つのガードは`chat-ui-with-audit-trail`の設計そのものと矛盾していた——ガードを「本文が空でない」だけに絞り、`#send-btn`はbusy中も無効化しないよう修正。修正後、2件連続送信で実際に`"1 prompt(s) queued"`と表示され、両ターンとも正しく完了することを確認した。
- [x] **Sessionsのrefreshボタン**——再現できなかった。ボタン自体・状態更新（`io-status`のセッション数表示）はテストで正しく動作した。ただしコードレビューで、`#right-tab-bar`のクリックを`initTabs()`と`initIo()`の2箇所で別々に監視しており、同じクリックで`ioLoadSessions()`が二重に走りうる潜在的な競合を発見・解消した（1本のハンドラに統合）。あわせて、refresh時に展開中の`<details>`行が問答無用で閉じてしまう（せっかく開いたトレースが消える）UXも直し、展開中のセッションIDを記憶して再展開＋トレース再取得するようにした。ユーザーが遭遇したのはこのUXの方だった可能性がある——再度確認をお願いしたい。
- [x] **System Logタブ**——3件の報告の後、ユーザーが追加で発見。`console.js`の元コメント通り、System Logタブは元々バックエンド無しの空タブだった。`quarkus-chat-ui3`の`LogTap`（`java.util.logging`のルートロガーに`Handler`を1個ぶら下げ、直近1000件をメモリに保持するグローバルなログ集積——`ChatSession.getRecentLogs()`とは別物で、こちらはJVM全体・全ロガーが対象）を無改名で移植し、`GET /api/logs`（`LogsResource`、新規）で公開、`console.js`のログ描画・レベルフィルタ・自動更新ロジックもほぼそのまま移植した。ブラウザ実機で確認——起動時のQuarkusログ7件が表示され、レベルフィルタ（ERROR以上）を選ぶと0件になることも確認した。

## 計画（タブ切替UI — 複数`ConversationTab`をブラウザから切り替える）

`010_ChatResourceAndSseConnection_260825_oo01`が"最小実装"として`TAB_ID`を`"alpha"`固定にしたまま残していた分。バックエンド（`ChatUiActorSystem.createTab`・`ChatResource`の各エンドポイントは既に`tabId`パラメータを取る設計）は複数タブに対応済みで、無いのはブラウザ側のUIだけ。

- [x] `ChatUiActorSystem`に`List<String> getTabIds()`を追加（`tabs`マップのキー一覧）
- [x] `ChatResource`に`GET /api/tabs`（一覧）・`POST /api/tabs/{tabId}`（作成、既存なら無視——`createTab`が既に冪等）を追加
- [x] `console.html`にタブバー要素を追加（`#right-tab-bar`と同じ見た目の`.rtab-btn`スタイルを再利用）
- [x] `app.js`：`TAB_ID`を可変にし、`switchTab(tabId)`（EventSource張り替え・chatArea再構築・conversation/models/queueの再取得）を実装
- [x] `app.js`：起動時に`GET /api/tabs`でタブ一覧を取得してタブバーを描画、直前に見ていたタブは`localStorage`（`theme`と同じパターン）で復元
- [x] 「+ New Tab」ボタン——新規タブIDを生成し`POST /api/tabs/{id}`で作成、`switchTab`で切り替え
- [x] ブラウザ実機（Playwright）で、2タブ以上を作成→切り替え→それぞれ別々に会話が進むこと、リロード後もlocalStorageで直前のタブへ戻ることを確認

## 計画（Stage 3 — プロンプト構築サブワークフローの差し替え）

`040_ChatSessionPorting_260823_oo01`の2-b-i-①〜③が定めた設計。現在の`stepExpectingAction()`は`String promptToSend = (stepCount == 1) ? (SYSTEM_PROMPT + "\n\n" + pendingPrompt) : pendingPrompt;`とプロンプトを直接組み立てており、サブワークフロー経由になっていない——`100_ChatSessionAgentLoop_260823_oo01`のUnder the Hoodが触れていない、未着手のまま残っていた部分。

- [x] 既定のプロンプト構築サブワークフローYAML（`prompt-construction-default.yaml`）を新設——`buildDefaultPrompt()`を呼ぶだけの単純な実装
- [x] `stepExpectingAction()`の直接構築を、`Interpreter.call(promptWorkflowFile)`によるサブワークフロー呼び出しに置き換え。計画では`pendingPrompt`自体を`List<String>`相当にする想定だったが、実装では`pendingPrompt`はそのまま残し、別に`Deque<String> constructedPrompts`（サブワークフローが`appendConstructedPrompt(...)`で積む）を新設した——`pendingPrompt`は「次にサブワークフローへ渡す元ネタ」、`constructedPrompts`は「サブワークフローが組み立てた、実際にproviderへ送る文字列の列」という別の役割なので、型を差し替えるより素直だった
- [x] サブワークフローが返す複数プロンプトを順に`provider`へ渡すループ——`stepExpectingAction()`はキューが空の時だけサブワークフローを呼び直し、空でなければ1件popして送るだけ。「ツール呼び出し無し、かつキューにまだ残っている」を`hasMoreConstructedPrompts()`で判定し、`chat-session-agent-loop.yaml`に`think-continue`遷移（`think`→`think`の自己ループ）を追加してターンを終わらせず次のプロンプトへ回す——計画には無かった追加だが、複数プロンプトを1ターン内で送るには必須だった
- [x] サブワークフロー用の子アクターのライフサイクル——`Interpreter.call(...)`が既に4段階（子生成・YAML読込・実行・`finally`での子削除）を内蔵しており、自前で`removeChildActor`を書く必要は無かった（計画時点ではこの既存メソッドの中身を確認していなかった）
- [x] ユニットテスト2本（`ChatSessionPromptWorkflowTest`）——既定サブワークフローが以前と同じテキストを組み立てることの確認、カスタムサブワークフロー（テスト専用YAML）が2件のプロンプトをそれぞれ別のLLM呼び出しとして順に送ることの確認
- [x] 実機で、既定サブワークフロー経由でも実際のcalcツール呼び出し→最終回答という既存の挙動が壊れていないことを確認（Sessionsタブのトレースで`REQUEST:`にシステムプロンプト込みの全文が変わらず記録されていることも確認）。複数プロンプト分割の実機確認（本物のLLMでの動作）はユニットテストの範囲に留まり、まだ行っていない

## 計画（タブ単位のログ階層 — 右ペインSessions・System Logの連動）

設計文書: `doc_SCIVICS003/docs/chat-ui-with-audit-trail/030_development/010_skeleton/150_TabScopedLogging_260826_oo01`。
`plugin-log-db`・`plugin-log-output`（`Turing-workflow-plugins`）を再利用する形に設計を直した経緯も同文書に記録。

- [x] `IoLogStore`の`sessionId`を単一フィールドからタブIDキーの`Map`へ変更、`workflowName`にタブIDを乗せる
- [x] `ChatSession`に`tabId`フィールド＋`setTabId`、`ChatUiActorSystem.createTab`から配線
- [x] `SessionsResource`に`tabId`クエリパラメータを追加し`workflowName`で絞り込み
- [x] `pom.xml`に`plugin-log-output`依存を追加
- [x] `RecentEntriesAccumulator`（環状バッファAccumulator）・`ForwardingAccumulator`（タブ→システムへの委譲）を新規作成
- [x] `ChatUiActorSystem`にシステム全体用`"outputMultiplexer"`＋タブごとの`"tab-<id>.log"`アクターを配線、`MultiplexerLogHandler`をルートロガーに追加
- [x] `ChatSession`・`PromptQueue`の主要ログ箇所に、既存の`LOG.xxx()`はそのまま残しつつタブ用Multiplexerへの呼び出しを追加
- [x] `GET /api/tabs/{tabId}/log`・`GET /api/system-log`を新設
- [x] `console.js`：Sessionsは`tabId`クエリパラメータ付きで、System Logは`GET /api/tabs/{tabId}/log`（形式差は`fromTabLogShape()`で吸収）で取得するよう変更。タブ切替時に両方を即座に再取得
- [x] `mvn install`（テスト含む）成功、ポート28019へ実機デプロイし、`curl`とPlaywrightでSessions・System Logがタブ追従することを確認

## レビュー

- 当初案（`TabLog`/`SystemLog`を新規のアクタークラスとして1から設計）は、既存の再利用可能プラグイン（`plugin-log-db`・`plugin-log-output`）を確認せずに進めていたと指摘を受け、破棄して書き直した。結果、新規に書いたのは`RecentEntriesAccumulator`・`ForwardingAccumulator`の2クラスのみで、残りは全て既存プラグインの再利用で済んだ。
- Sessions側の直し方も、当初検討していた`node_id`活用案（`"agent"`固定値をタブIDに変える）から、`sessionId`自体をタブごとに分ける案へ変更した——`"agent"`という値はタブ混在バグと無関係な、別の情報（誰が出したログか）を表していたため。
- `GET /api/system-log`（システム全体集約ビュー）は、明示的なタブ→システム転送と、`MultiplexerLogHandler`によるルートロガー経由の捕捉の両方で同じログが二重に入る既知の制限が残っている——現時点でこのエンドポイントを参照するUIが無いため実害は無いが、将来UIに繋ぐ際は要検討。
- ポート28014（ユーザーの常用インスタンス）は今回未反映。デプロイする場合は要相談。

## 計画（アクター命名リネーム tab-*→chat-*、Agent Loopタブ新設）

設計文書: `doc_SCIVICS003/docs/chat-ui-with-audit-trail/030_development/020_implementation/160_ChatIdRenameAndAgentLoopTab_260827_oo01`。

- [x] `tab-alpha`/`tab-beta` → `chat-01`/`chat-02`（アクター名接頭辞`tab-`→`chat-`、既定ID`alpha`/`beta`→`01`/`02`）——`ChatUiActorSystem`・`ChatSession`・`app.js`・`console.js`・テスト
- [x] `ChatSessionIIAR.AGENT_LOOP_YAML`（`static final`固定値）を、`ChatSession.promptWorkflowFile`と同じ形の、タブごとに持てる`agentLoopWorkflowFile`フィールドに変更
- [x] `GET /api/tabs/{tabId}/workflows`・`GET /api/tabs/{tabId}/workflows/{name}`を新設（agent loop + プロンプト構築サブワークフローの2件をカタログとして返す）
- [x] 死んでいた「Workflow」タブを削除、`quarkus-chat-ui3`の実装を移植した「Agent Loop」タブ（読み取り専用YAMLビューア）に差し替え、タブ連動を配線
- [x] `mvn install`（テスト含む）成功、ポート28019へ実機デプロイし、Playwrightで一連の動作を確認

## レビュー

- 当初「タブごとに専用のagent loop YAMLファイルを作る」という誤解をしたまま実装を進めかけ、ユーザーから訂正を受けた。正しくは、既存の再利用可能なワークフローファイル群への参照をタブごとに持たせる、という`promptWorkflowFile`と同じ構造——複数タブが同じファイルを共有するのが自然な使い方。
- ポート28014（ユーザーの常用インスタンス）は今回も未反映のまま。
