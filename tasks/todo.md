# 030_development/010_skeleton の再構成と更新

## 何をするか

37個の設計文書が `030_development/010_skeleton/` に平らに並んでいる。これを8つの部に分け、
各文書の内容を現在のコードに合わせて更新する。

文書どうしの参照は本文中の `TabActorTree_260721_oo01` のような id 表記であり、
ディレクトリを移してもフロントマターの `id` が変わらなければ壊れない。
番号は Docusaurus の並び順を決めるだけなので、部の中で振り直す。

## 部の構成

- [ ] 010_骨組み — CDIとActorSystemの同居、ブラウザとの境界、workflowからの呼び出し口
      010_TabActorTree / 090_ChatResourceDesign / 110_ChatSessionIIAR
- [ ] 020_移植 — quarkus-chat-ui/core から持ってくる作業
      020_ChatActor / 030_ChatSessionActorRenaming / 040_ChatSessionPorting /
      050_SseConnectionPorting / 060_PromptQueuePorting / 070_SideQuestionPorting /
      080_McpRequestQueuePorting
- [ ] 030_ブラウザへの配信 — SSE
      120_SseInQuarkus / 130_SseStreamPerRequest / 140_SseDecoupledConnection
- [ ] 040_agent_loop — 1つの会話の中の動き
      100_ChatSessionAgentLoop / 190_WorkflowReloadReset / 350_TurnResourceLimits /
      330_SkillAndAgentsFile / 340_FileAccessScope / 320_DocRetrievalAgentLoop
- [ ] 050_会話どうしの連携
      160_AskChatToolAndWatchdog / 200_AskChatNestedTimeout / 220_CollaborationGraph /
      170_WorkerBabysitterOrchestration / 210_BabysitterLoopWorkflowShape /
      290_GenericBabysitterPhases / 230_BabysitterRealisticE2eScenario / 250_ParallelWorkerPool
- [ ] 060_計画の実行 — PlanRunner
      240_PlanRunnerLifecycleManagement / 300_DirectPlanSubmission / 310_PlanWorkerSlot
- [ ] 070_アクターツリーの形
      260_DynamicActorNaming / 270_ProjectScopedActorTree / 280_ProjectNamespacePrefix /
      360_NestedConversationTree
- [ ] 080_動いているものを見る — 監査証跡
      150_TabScopedLogging / 180_BusyStateReadableSnapshot / 370_ActorPurposeFromWorkflowNote

## 内容の更新

- [ ] 010_TabActorTree：ツリーの図と用語定義が現在と違う（Project・Housekeeper・入れ子会話が無い、
      名前が `tab-<tabId>` のまま、`ChatUiActorSystem` を「旧設計のまま」と書いている、
      `claude` CLI プロセスを子として描いている）
- [ ] 実機確認が空欄の4件：200_AskChatNestedTimeout / 230_BabysitterRealisticE2eScenario /
      260_DynamicActorNaming / 320_DocRetrievalAgentLoop（320は別見出しで書いたので統合）
- [ ] 残り32件を1つずつ読み、現在のコードと食い違う記述を直す
- [ ] `ConversationTab` の javadoc が旧クラス名5つを参照している（コード側の修正）

## 進め方

1文書ずつ順に読んで直す。並列・バックグラウンドは使わない。
