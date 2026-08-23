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

- `web_search`・`search_docs`はagent loop（LLM経由）でのラウンドトリップ未確認（ツール単体は確認済み）。
- `060_PromptQueuePorting_260823_oo01`にあった`noThink`引数は、`PromptQueue.dequeueAndSend`の新しい呼び出し（`chat.start(...)`）に渡していない（`start`のシグネチャに無い）——Qwen3以外のモデルでは今のところ影響しないが、明示的な欠落として記録する。
