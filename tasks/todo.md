# Claude Code / Codex をハーネス provider として会話ごとに選べるようにする

設計文書: `doc_SCIVICS003/docs/chat-ui-with-audit-trail/030_development/010_skeleton/100_providers/010_CliHarnessProvider_260912_oo01`
ブランチ: `CliHarnessProvider_260912_oo01`（BranchingBrief に従い設計文書の識別子）

## 手順

- [x] 1. 設計文書を書く
- [x] 2. ブランチを切る
- [x] 3. `ChatEvent` に `toolUse` / `toolResult` を追加し、`ChatSession` がそれを `turnN/stepM/tool` として I/O ログに記録する（provider 非依存の受け口）。ユニットテスト
- [x] 4. ツール分担 `ToolSet`（full / collaboration）を `ChatSession` に持たせ、システムプロンプトのツール一覧と `executeTool` の受理をそれに従わせる。ユニットテスト
- [x] 5. `quarkus-chat-ui` の `cli/process`（CliProcess, StreamEventParser, StreamEvent, CliConfig）を `com.scivicslab.chatui.harness` へ移植し、`tool_use` / `tool_result` を全文で出すよう拡張。`ClaudeCodeProvider`。ユニットテスト（パーサ）
- [x] 6. `ChatUiActorSystem.setProvider(project, chat, kind, toolSet)`：provider 子アクターを同名で差し替える。REST `POST /api/projects/{p}/chats/{c}/provider`、`status` に provider を載せる。ユニットテスト
- [x] 7. 画面：`#provider-select` を `#model-select` の隣に置き、切替で POST → モデル一覧再取得
- [x] 8. `CodexProvider`（実装・パーサのユニットテストまで。実機は `codex login` 待ち）（`codex exec --json` を 1 ターン 1 プロセス、`resume` で継続）。ユニットテスト（パーサ）
- [x] 9. ビルド（`rm -rf target; mvn install`）→ 実機で claude 会話 1 往復、ハーネス内部ツール呼び出しが Sessions タブに出ることを確認 → 設計文書に実機確認を追記
- [ ] 10. codex 実機確認 → 設計文書に追記（ブロック中: このマシンの codex は refresh token 失効で 401。`codex login` 後に再開）

## Review

- ユニットテスト 119 件 green（`rm -rf target; mvn install`）。新規: `StreamEventParserTest` 5、`CodexEventParserTest` 4、`ChatSessionHarnessToolIoTest` 1、`ChatSessionToolSetTest` 3、`ChatUiActorSystemSetProviderTest` 2。
- 実機（使い捨てポート 28039、project1 の working dir を `chat-ui-scratch/harness-trial`）:
  - `POST .../provider {"provider":"claude"}` → status が `claude`/`collaboration`、models が Claude の一覧に変わる。
  - Claude Code に Write を使わせる指示 → ファイル実作成、I/O ログ `turn1/step1/tool` に `TOOL: Write` の入力と結果が全文で記録、`turn1/step1/llm` に最終回答。
  - `{"provider":"claude","tools":"full"}`（素モデル）→ Claude が `<invoke name="read">` を書き、`ChatSession` が実行。`turn2/step1..3` に llm/tool が交互に記録。セッションファイルで `--resume` が効いた。
  - `ProviderSelectE2E`（Playwright main）10 項目 green: ドロップダウンがサーバの値を映す、切替でモデル一覧が入れ替わる。
- 未了: Codex の実機確認（認証切れ）。
