# ハーネスの会話のツール分担を「重なるものだけ除く」に変え、前置きの責任を provider に移す

設計文書: `doc_SCIVICS003/.../100_providers/040_HarnessPrefaceAndToolSplit_260912_oo01`
ブランチ: 不要（1 コミットでビルドが通る。main に直接）

## 手順

- [x] 1. 設計文書を書く
- [x] 2. `ToolSet`: `COLLABORATION` を `HARNESS` に改名し、意味を「登録済み全部から `read`/`write`/`calc` を除く」にする
- [x] 3. `LlmProvider.promptPreface()`（既定は空）。`ClaudeCodeProvider` と `CodexProvider` が自分の前置きを返す。`ChatSession` は `HARNESS_PREFACE` を捨て、provider の前置きを付ける
- [x] 4. `ChatResource` の既定ツール分担を factory の `defaultToolSet()` から取る
- [x] 5. テスト更新（ToolSet、ChatSession、SetProvider、PluginRegistry、E2E）、`HarnessPluginTest` 追加。ビルド green
- [x] 6. 既存文書の `collaboration` を `harness` に直す。tutorial の表
- [x] 7. 再配置・両タイル再起動。ハーネスの会話の最初のプロンプトに `web_search` と `fetch` が載ること、天気の質問が答えられることを確認。E2E
- [x] 8. コミット・push

## Review

- ユニットテスト 126 件 green（`HarnessPluginTest` 4 件を含む）。
- cloud-llm 版 28031 で新しい `claude` の会話: 最初のプロンプトに `web_search`/`fetch` が載り `read` は載らない。天気の質問で `ToolSearch`→`WebSearch`→`WebFetch` が記録され回答。`ProviderSelectE2E` 10 項目 green。
- 前置きの文言を 1 回強めた（一覧のツールをハーネスのツールとして `tool_use` で呼んで失敗していた）。
- 設計文書 `HarnessPrefaceAndToolSplit_260912_oo01` と既存 4 文書を更新、push 済み。
