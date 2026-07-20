# Townia API v1

Townia API は、Towny を利用していた連携プラグインを Townia 向けに移植するための Bukkit サービス API です。Towny の `com.palmergames.bukkit.towny.*` をバイナリ互換で提供するものではありません。連携先は `Townia` に `depend` または `softdepend` を設定し、本 API を使ってください。

## 導入

`plugin.yml`:

```yml
depend: [Townia]
```

コンパイル時は Townia JAR を `compileOnly` に追加します。起動後に Bukkit のサービスから取得します。

```kotlin
val townia = TowniaAPI.get() ?: return
val town = townia.getTownOfPlayer(player.uniqueId).orElse(null) ?: return
```

Java からも `TowniaAPI.get()` を同様に呼べます。`get()` が `null` の場合は、Townia が未導入・無効、またはまだ有効化されていません。

## API 一覧

| 分類 | 主なメソッド |
| --- | --- |
| 検索 | `getTown(UUID/String)`, `getTownOfPlayer(playerId)`, `getNation(UUID/String)`, `getResident(UUID/String)`, `getPlot(...)` |
| 列挙 | `towns()`, `nations()`, `residents(townId)`, `residentsInNation(nationId)`, `plots(townId)`, `plotsOwnedBy(playerId)` |
| 町 | `createTown`, `deleteTown`, `renameTown`, `setTownSpawn`, `setTownPublic`, `setTownMayor`, `addTownBalance`, `subtractTownBalance` |
| 国家 | `createNation`, `deleteNation`, `addTownToNation`, `removeTownFromNation`, `setNationLeader` |
| 区画 | `claim`, `unclaim`, `setPlotType`, `setPlotForSale`, `setPlotOwner` |
| 住民 | `setResidentTown`, `clearResidentTown` |

完全なシグネチャは `net.azisaba.townia.api.TowniaAPI` が正です。検索は `Optional` を返します。列挙メソッドは呼び出し時点のリストを返すため、リストの編集は Townia の状態を変更しません。

## データ型と永続化

返される `Town`、`Nation`、`TowniaPlayer`、`Plot` は Townia が管理するライブオブジェクトです。フィールドを直接変更しても保存や整合性検査は保証されません。状態変更は必ず `TowniaAPI` の操作メソッドを使ってください。

操作がルール違反・存在しない対象・残高不足・DB エラーで失敗すると `TowniaException` が送出されます。`messageKey` は Townia のメッセージキーです。経済操作の金額は正の有限値だけを渡してください。ゼロ・負数・`NaN`・無限値では `IllegalArgumentException` になります。

## スレッドと互換性

全メソッドは Bukkit のメインスレッドで呼び出してください。非同期処理から使う場合は Bukkit scheduler でメインスレッドへ戻してください。API v1 ではメソッドの削除や意味の破壊的変更を行わず、追加は後方互換な形で行います。

Towny の旧 API を直接 import しているプラグインは、Townia API へソース移植が必要です。特に `TownyUniverse`、`Resident`、`TownBlock` は Townia には存在しないため、それぞれ `TowniaAPI` の検索、住民、区画メソッドに置き換えてください。
