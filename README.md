# Townia

AzisabaNetwork向けの独自Towny系プラグイン（Paper 1.21対応）。

## 概要
Towniaは、町（Town）や国（Nation）を作成し、土地（Plot）を保護したり、プレイヤー間で経済的なやり取りを行うことができるプラグインです。Vault連携により、町や国の維持費・拡張にサーバー内のお金を使用します。

## 機能一覧
- **町の作成と管理**: `/town new <名前>` で町を作成し、周辺のチャンクを保護（`/town claim`）できます。
- **国の作成と管理**: 複数の町が同盟を結び、国（Nation）を作成できます（`/nation new <名前>`）。
- **権限管理**: チャンクごとに、住人・同盟・外部プレイヤーがブロックの破壊や設置を行えるかを細かく設定できます（`/plot set perm ...`）。
- **役職システム**: 町の中に「MAYOR（町長）」「CO_MAYOR（副町長）」「ASSISTANT（補佐）」などの役職を設定し、権限を委譲できます。
- **フレンド機能**: 個人単位でフレンドを追加でき、フレンド専用の権限設定も将来的には拡張可能です（`/resident friend add <名前>`）。
- **経済システム**: Vaultと連携し、維持費（Upkeep）を毎日自動徴収するシステムが備わっています。

## コマンド一覧

### 町関連 (`/town`)
- `/town new <名前>` - 新しい町を設立します
- `/town delete` - 町を解散します
- `/town claim` - 現在のチャンクを町の土地として保護します
- `/town unclaim` - 現在のチャンクの保護を解除します
- `/town spawn` - 町のスポーン地点にテレポートします
- `/town here` - 現在地の町の情報を表示します
- `/town info [町名]` - 町の情報を表示します
- `/town list` - 町のリストを表示します
- `/town invite <プレイヤー>` - プレイヤーを町に招待します
- `/town rank add/remove <プレイヤー> <役職>` - プレイヤーの役職を変更します
- `/town leave` - 町から脱退します
- `/town kick <プレイヤー>` - プレイヤーを町から追放します

### 国関連 (`/nation`)
- `/nation new <名前>` - 新しい国を設立します
- `/nation delete` - 国を解散します
- `/nation invite <町名>` - 町を国に招待します
- `/nation kick <町名>` - 町を国から追放します
- `/nation ally add/remove <国名>` - 他の国と同盟を結ぶ・破棄します
- `/nation enemy add/remove <国名>` - 他の国を敵対国に設定・解除します
- `/nation info [国名]` - 国の情報を表示します
- `/nation list` - 国のリストを表示します

### 土地関連 (`/plot`)
- `/plot set perm <対象> <権限> <on|off>` - チャンクの権限を設定します
- `/plot set type <タイプ>` - チャンクの種類（COMMERCIAL等）を変更します
- `/plot forsale <価格>` - チャンクを売りに出します
- `/plot notforsale` - チャンクの販売を中止します
- `/plot claim` - 売りに出されているチャンクを購入します

### 住人関連 (`/resident`)
- `/resident info [プレイヤー]` - 住人情報を表示します
- `/resident list` - 住人リストを表示します
- `/resident friend add/remove <プレイヤー>` - フレンドを追加・削除します

### 管理者用 (`/towniaadmin`)
- `/towniaadmin reload` - 設定ファイルを再読み込みします
- `/towniaadmin bypass` - 全ての保護を無視して建築等を行えるようになります
- `/towniaadmin setclaim <町名>` - 指定したチャンクを強制的に町の土地にします
- `/towniaadmin unclaim` - 指定したチャンクを強制的に保護解除します
- `/towniaadmin deletetown <町名>` - 町を強制解散します

## データ構造
MySQLデータベースを利用し、非同期でデータを読み書きします。
- `townia_towns` - 町の情報
- `townia_nations` - 国の情報
- `townia_residents` - プレイヤー情報
- `townia_plots` - チャンク情報

## 開発環境
- Java 21
- Paper API (1.21.x)
- Vault API (Economy)
- MiniMessage (テキストフォーマット)
