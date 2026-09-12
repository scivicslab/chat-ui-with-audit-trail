# LLM 要求の失敗を会話に残す／キューを位置つきチェックリストにする

設計文書:
- `doc_SCIVICS003/.../030_development/020_implementation/220_TurnErrorInConversation_260913_oo01`
- `doc_SCIVICS003/.../030_development/020_implementation/230_QueueChecklistView_260913_oo01`
ブランチ: 不要（各 1 コミットでビルドが通る。main に直接）

## 手順

- [x] 1. 設計文書 2 件を書く
- [x] 2. (A) `ChatSession`: provider の `error` を `turnError` に取り、答えが空なら turn を失敗として終える。履歴に role `error`、I/O ログの llm 段に `ERROR:`、`turnN/conversation` に `ERROR:`。空の `delta` を出さない
- [x] 3. (A) `IoLogView.Turn` に `error` を足し、復元時に role `error` を戻す。ユニットテスト
- [x] 4. (B) `PromptQueue` に送出済み一覧（上限 100）。`popFront` で積む、`sentSnapshot`/`removeSentAt`/`seedSent`。`GET .../queue` が `sent` と `pos` を返す。`DELETE .../queue/sent/{i}`。復元した会話の質問を送出済みに種まき。ユニットテスト
- [x] 5. (B) `app.js`: quarkus-chat-ui と同じ描画（sent/current/waiting、見出し、Save、領域の表示規則、`user` イベントで再描画、リサイズ）
- [x] 6. ビルド green → 28014 に配置・再起動 → 実機確認（失敗ターンが会話とログに残る／キューの見え方）→ E2E `QueueChecklistE2E`
- [x] 7. 設計文書に実機確認を追記、コミット・push

## Review

- 130 件 green。28014 で実機確認、`QueueChecklistE2E` 8 項目 green。設計文書 2 件に実機確認を追記、push 済み。

## 追加: 会話の設定の記録と復元（ConversationSettingsRecord_260913_oo01）
- [x] `settings` 記録（provider/tools/model）、復元で適用（記録は書かない）、`GET /api/sessions/{id}/settings`、Sessions タブに履歴。133 件 green、`SessionSettingsE2E` 3 項目 green。push 済み。
