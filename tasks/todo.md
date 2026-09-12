# provider とツールをプラグイン jar に分離し、3 種類の起動構成を作る

設計文書: `doc_SCIVICS003/docs/chat-ui-with-audit-trail/030_development/010_skeleton/100_providers/030_ProviderAndToolPlugins_260912_oo01`
tutorial: `doc_SCIVICS003/docs/chat-ui-with-audit-trail/050_tutorials/010_ThreeStartupConfigurations_260912_oo01`
ブランチ: `ProviderAndToolPlugins_260912_oo01`

## 手順

- [x] 1. 設計文書を書く
- [x] 2. ブランチを切る
- [x] 3. Maven を親 pom + `plugin-api` + `app` に分け、`LlmProvider`/`ProviderContext`/`ProviderCapabilities`/`ChatEvent`/`ToolSet` を `plugin-api` へ移す。ビルド green
- [x] 4. SPI（`ChatUiPlugin`/`LlmProviderFactory`/`ProviderCreationContext`/`ConversationTool`）と `PluginRegistry`（`chat-ui.plugins` の jar を起動時に読む）。組み込み `openai-compat` factory。`newProvider` を登録簿引きに。`GET /api/plugins`。ユニットテスト
- [x] 5. 画面の provider ドロップダウンをサーバの選択肢から作る
- [x] 6. `plugin-web-tools`（`web_search`/`fetch`）。`ChatSession` が登録簿のツールを説明・実行・要約する。ユニットテスト
- [x] 7. `plugin-harness`（`harness` パッケージ一式 + factory + `META-INF/services`）。ユニットテスト移動
- [x] 8. `rm -rf */target target; mvn install` green → 28039 で 3 構成を実機確認（`/api/plugins`、システムプロンプトのツール一覧、claude 1 往復）。`ProviderSelectE2E` 更新
- [x] 9. tutorial を書く。設計文書に実機確認を追記
- [ ] 10. コミット

## Review

- 4 モジュール（親 / `plugin-api` / `app` / `plugin-web-tools` / `plugin-harness`）。ユニットテスト 122 件 green。plugin jar に api の複製なし、services エントリあり。本体に `harness` と `WebSearchTool` なし。
- 28039 で 3 構成を実機確認（`/api/plugins`、claude 拒否、プロンプトのツール一覧、fetch の記録、Claude Read の記録、`ProviderSelectE2E` 10 項目）。
- tutorial `ThreeStartupConfigurations_260912_oo01`、設計文書、手順書を更新。
