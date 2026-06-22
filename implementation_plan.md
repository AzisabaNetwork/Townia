# Townia Major Feature Implementation Plan

The goal is to reach parity with standard Towny features, including Home blocks, Outposts, Friends, an advanced Permission matrix, a daily tax/upkeep system, and Action Bar notifications.

## Proposed Changes

### 1. Database Schema Migrations (`DatabaseManager.java`)
- **`towns` table**:
  - Add `homeblock_world`, `homeblock_x`, `homeblock_z` (to store the town's home chunk).
  - Add `perms_resident`, `perms_ally`, `perms_outsider`, `perms_nation` (strings, e.g., `BDSI` representing Build, Destroy, Switch, Item) to replace simple global booleans.
  - Add `daily_upkeep` (double).
- **`plots` table**:
  - Add `is_outpost` (boolean) to distinguish outpost claims.
  - Add `perms_resident`, `perms_ally`, `perms_outsider`, `perms_nation` (override town permissions per plot).
- **`nations` table**:
  - Add `spawn_world`, `spawn_x`, `spawn_y`, `spawn_z`, `spawn_yaw`, `spawn_pitch`.
- **`resident_friends` table** (NEW):
  - `resident_uuid`, `friend_uuid` to store friend relationships.

### 2. Core Logic (`TownManager.java`, `PlotManager.java`, `NationManager.java`)
- **Home Block**: When a town is created, the first claimed chunk is set as the `homeBlock`. The default town spawn will be the center of this chunk.
- **Outposts**: `/town claim outpost`. Allows claiming chunks disconnected from the home block, provided the town has enough balance (if there is an outpost cost).
- **Daily Task**: Implement an asynchronous scheduler task (e.g., running every midnight in-game or real-time depending on config) that:
  - Deducts `daily_upkeep` from Town bank.
  - Deducts taxes from Residents into Town bank.
  - Deducts Nation taxes from Town bank into Nation bank.
  - If a town cannot pay upkeep, it falls into ruin/is deleted.
- **Nation Spawn**: Add `/nation spawn` and `/nation set spawn` commands.

### 3. Permissions Matrix (`PlotProtectionListener.java`)
- Rework block break/place, interact, and damage events.
- Check the player's relationship to the plot: `Resident` (member of the town), `Ally` (friend, or member of an allied nation), `Nation` (member of the same nation), or `Outsider`.
- Check if the specific action (`Build`, `Destroy`, `Switch`, `Item Use`) is allowed for their relationship group in the plot (or fallback to town perms).

### 4. Player Action Bar (`PlayerMoveListener.java` - NEW)
- Listen to `PlayerMoveEvent` (or a more optimized custom chunk enter event).
- When a player moves from one chunk to another:
  - Detect if the new chunk belongs to a town.
  - Send an Action Bar message: `[TownName] - Mayor: [MayorName]` or `Wilderness` if unclaimed.

### 5. Friends System (`ResidentCommand.java`, `ResidentManager.java`)
- Add `/resident friend add <player>`, `/resident friend remove <player>`, `/resident friend list`.
- Friends are treated as "Allies" in the permission matrix for plots owned by the resident.

### 6. `/town info` Rewrite
Rewrite the `/town info` display to exactly match your requested format:
```text
Board: [message]
Founded: 〇〇
Town Size: current/max [Nation Bonus: ◯][Home: x,z]
Outpost: count
Permissions: Build = ---- Destroy = ---- Switch = ---- Item = ----
Explosions: on/off
Fire: on/off
Mob Spawns: on/off
Bank Balance: \0000
Upkeep/Day: \0000
Taxes: \0000
Mayor: MCID
Assistant [number]: players...
SubMayor [number]: players...
Nation: nation name
Residents: players
```

### 7. Language Updates (`lang_en.yml`, `lang_ja.yml`)
- Update messages to replace the generic "Chunk ({x}, {z}) has been claimed for {town}" with a cleaner, Towny-like message.
- Add translation keys for all new features (outposts, nation spawn, friends, upkeep, actionbar).

## Verification Plan
### Automated & Manual Verification
- Deploy changes to test server.
- **Create Town**: Verify first claim becomes Homeblock.
- **Action Bar**: Walk across chunk borders and verify Action Bar updates seamlessly without lag.
- **Outposts**: Claim an outpost away from the home block.
- **Permissions**: Test Build/Destroy/Switch/Item as an Outsider and as a Resident.
- **Commands**: Check `/town info` UI structure, `/nation spawn`, and `/resident friend add`.

> [!CAUTION]
> **Schema Alteration:** We will modify the SQLite/MySQL database structure. Existing towns will have their homeblock set to the current spawn location's chunk, and existing plots will be mapped to the new permission schema.

> [!IMPORTANT]
> **User Review Required:** Do you want the daily upkeep task to run based on **Real-World Time** (e.g., every 24 hours at midnight real time) or **In-Game Time** (every 20 minutes)? Towny usually defaults to real-world daily.
