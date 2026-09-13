# batch job の起動でパラメータをフォームから受ける

設計文書:
- `doc_SCIVICS003/.../030_development/020_implementation/250_JobParameterForm_260913_oo01`
ブランチ: 不要（1 コミットでビルドが通る。main に直接）

方針: 手入力のみ。パラメータファイル（actor-IaC の overlay の `vars:`）の読み込みは入れない（設計文書の Under the Hood に理由を記載）。

## 手順

- [x] 1. 設計文書を書く
- [x] 2. `ProjectWorkflowCatalog`: `ParamSpec`（`key`/`label`/`description`/`type`/`required`/`defaultValue`/`options`）と `Document.params`。`params:` があればそれを読み、無ければ本文の `${key}` を走査（`${result}` は除く）
- [x] 3. ユニットテスト: 宣言の 6 項目、宣言なしの走査、`${result}` の除外
- [x] 4. `ProjectJobResource.start`: body に `parameters` を受け、`validate` → 必須の確認 → 既定値の補完 → `startJob` の順。`required` の欠けは 400
- [x] 5. `Project.startJob`: `putJson` で値を runner の JSON 状態へ置き、最初のログ行に使った値を書く
- [x] 6. ユニットテスト: ジョブの中から与えた値を読める、最初のログ行に残る（`required` 欠けの 400 は実機で確認）
- [x] 7. `console.html` の `tab-jobrun` をフォームに、`console.js` に入力欄の生成と Run の送信
- [x] 8. ビルド green → 稼働中の 28014 には触れず同じ jar を 28031 で起動して実機確認（params の取得、必須欠けの 400、既定値の補完、実行中の読み出し、ログ行）
- [x] 9. E2E（Playwright の `main()`）
- [x] 10. 設計文書に実機確認を追記
- [ ] 11. コミット・push（push は確認を取る）→ AI-workspace の Build Snapshot → 再起動（ユーザー）

## Review

143 件 green。稼働中の 28014 には触れず、同じ jar を 28031 で起動して実機確認（params 3 件の取得、必須欠けの 400、既定値の補完、`state.getString`/`getInt` での読み出し、最初のログ行）。`JobParameterFormE2E` 11 項目 green。設計文書に実機確認を追記済み。

実装中に分かったこと: `turing-workflow` 4.1.0 は本文の `${key}` を展開しない。値の受け渡しは runner の JSON 状態（`putJson`）で、ワークフローは `jexl:state.getString('key')` で読む。設計文書の Under the Hood に実測として記載した。

残り: コミットと push（push は確認を取る）。28014 への反映は push 後に AI-workspace の Build Snapshot、再起動はユーザー。

---

# scholar_search（OpenAlex）と fetch の PDF 抽出を plugin-web-tools に足す（2026-09-13）

`project1/chat-01` の turn 8 で、`web_search` が返した論文 10 件のうち本文を読めたのは 2 件だけだった（4 件は PDF のバイト列、4 件はページ取得失敗）。学術的な問いに Web 検索は向かないので、OpenAlex を引く `scholar_search` を plugin-web-tools に足し、`fetch` が PDF を PDFBox で文字にするようにする。English Toolkit の AI Chat には足さない（ユーザー指示）。

- [x] `ScholarSearchTool`（OpenAlex `title_and_abstract.search`、sort=relevance|cited、year_from、limit 1–25、3 秒の間隔、`chat-ui.openalex.mailto` は任意）
- [x] `FetchTool`: 本文をバイト列で受け、`application/pdf` か `%PDF-` なら PDFBox で先頭 60 ページを文字にする。文字コードは Content-Type の charset に従う
- [x] `WebToolsPlugin` に `scholar_search` を登録、`fetch` の説明に PDF を追記
- [x] pom: `pdfbox.version=3.0.3`。plugin では provided、app で compile（plugin の class loader の親は本体）
- [x] テスト: `ScholarSearchToolTest`（URL・limit・year・要旨の復元・整形）、`FetchToolPdfTest`（PDFBox で作った PDF の抽出・60 ページ上限・charset）
- [ ] `rm -rf */target && mvn install`、OpenAlex と PDF の実機確認
- [ ] 設計文書 `doc_SCIVICS003/.../100_providers/050_ScholarSearchAndPdfFetch_260913_oo01`
- [ ] 自分のファイルだけを commit（作業ツリーには別セッションの未コミット変更がある。`git add -A` を使わない）。push は確認を取る
