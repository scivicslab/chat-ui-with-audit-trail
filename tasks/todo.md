# 030_development/010_skeleton の再構成と更新

## 何をするか

37個の設計文書が `030_development/010_skeleton/` に平らに並んでいる。これを8つの部に分け、
各文書の内容を現在のコードに合わせて更新する。

文書どうしの参照は本文中の `TabActorTree_260721_oo01` のような id 表記であり、
ディレクトリを移してもフロントマターの `id` が変わらなければ壊れない。
番号は Docusaurus の並び順を決めるだけなので、部の中で振り直す。

## 部の構成

- [x] 010_骨組み — CDIとActorSystemの同居、ブラウザとの境界、workflowからの呼び出し口
      010_TabActorTree / 090_ChatResourceDesign / 110_ChatSessionIIAR
- [x] 020_移植 — quarkus-chat-ui/core から持ってくる作業
      020_ChatActor / 030_ChatSessionActorRenaming / 040_ChatSessionPorting /
      050_SseConnectionPorting / 060_PromptQueuePorting / 070_SideQuestionPorting /
      080_McpRequestQueuePorting
- [x] 030_ブラウザへの配信 — SSE
      120_SseInQuarkus / 130_SseStreamPerRequest / 140_SseDecoupledConnection
- [x] 040_agent_loop — 1つの会話の中の動き
      100_ChatSessionAgentLoop / 190_WorkflowReloadReset / 350_TurnResourceLimits /
      330_SkillAndAgentsFile / 340_FileAccessScope / 320_DocRetrievalAgentLoop
- [x] 050_会話どうしの連携
      160_AskChatToolAndWatchdog / 200_AskChatNestedTimeout / 220_CollaborationGraph /
      170_WorkerBabysitterOrchestration / 210_BabysitterLoopWorkflowShape /
      290_GenericBabysitterPhases / 230_BabysitterRealisticE2eScenario / 250_ParallelWorkerPool
- [x] 060_計画の実行 — PlanRunner
      240_PlanRunnerLifecycleManagement / 300_DirectPlanSubmission / 310_PlanWorkerSlot
- [x] 070_アクターツリーの形
      260_DynamicActorNaming / 270_ProjectScopedActorTree / 280_ProjectNamespacePrefix /
      360_NestedConversationTree
- [x] 080_動いているものを見る — 監査証跡
      150_TabScopedLogging / 180_BusyStateReadableSnapshot / 370_ActorPurposeFromWorkflowNote

## 内容の更新

- [x] 010_TabActorTree：ツリーの図と用語定義が現在と違う（Project・Housekeeper・入れ子会話が無い、
      名前が `tab-<tabId>` のまま、`ChatUiActorSystem` を「旧設計のまま」と書いている、
      `claude` CLI プロセスを子として描いている）
- [x] 実機確認が空欄の4件：200_AskChatNestedTimeout / 230_BabysitterRealisticE2eScenario /
      260_DynamicActorNaming / 320_DocRetrievalAgentLoop（320は別見出しで書いたので統合）
- [x] 残り32件を1つずつ読み、現在のコードと食い違う記述を直す
- [x] `ConversationTab` の javadoc が旧クラス名5つを参照している（コード側の修正）

## 進め方

1文書ずつ順に読んで直す。並列・バックグラウンドは使わない。

## レビュー

37文書を8つの部へ移し、内容を現在のコードに合わせて直した。ディレクトリの移動は `git mv` で行い、フロントマターの `id` は変えていないので、文書どうしの参照は壊れていない。

直した内容で大きいものは4つある。

1. `TabActorTree_260721_oo01` を書き直した。系統樹の図を `Project`・`Housekeeper`・入れ子の会話を含む現在の形にし、名前を `project1/chat-01` に直し、会話の子が4種であることを書き、`claude` CLI プロセスの記述を消した。提案したまま実装されていない `IIActorSystemProducer` は、実装されていないと明記して残した。
2. SSE の記述の誤りを直した。パターン2には生の Vert.x `Router` が要ると書いてあったが、`UnicastProcessor` をアクターのフィールドに持てば JAX-RS だけで組める。実際この製品はそうしている。
3. 実機確認の空欄4件を埋めた。`ask_chat` の `timeoutSeconds` は今回ポート28014で実際に走らせ、I/Oログに `"timeoutSeconds":"150"` が届いていることを確認した（検証用に会話 `vt-caller`・`vt-helper` を作った。会話を消す経路が無いので残っている）。
4. 作られなかったものを作られなかったと書いた。`SideQuestion`・`McpRequestQueue`・`StallMonitor`・`babysitter-N` 枠・会話を閉じる経路の5つである。

コード側の修正は1件。`ConversationTab` の javadoc が存在しない5クラスを参照していたので直した。`mvn install` とテスト22件は通っている。

`030_development/020_implementation` の18文書は今回の対象に含めていない。`DeferredItems_260826_oo01` には既に解決した項目（`search_docs` のラウンドトリップ、上位ワークフローからの操作、Stage 4）が未対応として残っている。

## 追加分：020_implementation と 010_concepts

`030_development/020_implementation` の18文書と `010_concepts` の5文書も見直した。

`020_implementation` はディレクトリ構造を変えていない。18文書は時系列の記録であり、番号がその順序を表している。主題で分けると、この一群を読める形にしている唯一の軸を壊す。

内容で直したのは次の5点である。

- `DeferredItems_260826_oo01` を「今も残っている項目」7件と「片付いた項目」4件に分け直した。片付いたのは `search_docs` のLLM経由ラウンドトリップ、上位ワークフローからの会話操作、Stage 4、カスタムサブワークフローの実機走行である。
- `ChatActorRename_260827_oo01` が「未着手」としていたRESTパスの改名は `ChatRestNamespace_260827_oo01` が済ませた。その `ChatRestNamespace` の経路もさらに深くなった。
- `AgentLoopTab_260827_oo01` が「今は1本しかない」と書いたワークフローファイルは7本になった。
- `ToolCatalog_260829_oo01` に `run_plan` が抜けていた。`read`/`write`/`search_docs` の説明も現在の動作に直した。`SYSTEM_PROMPT`・`executeTool` の分岐・この一覧の3つが11個で揃っていることを確認した。
- `Terminology_260829_oo01` の「定義に反している箇所」5件はすべて解消済みだった。`tabId` という名前は4箇所に残っている。

### 残っている判断

`SessionsResource` のクエリパラメータ `?tabId=` は、`Terminology_260829_oo01` が決めた `chatId` へ統一する規則から外れたまま残っている。渡している値は既に会話の完全名（`project1/chat-01`）である。直すなら `SessionsResource`・`console.js`・`DocRetrievalBenchmark` の3ファイルを同時に変える。URLに現れる公開の名前なので、ユーザーの判断を仰ぐ。
