package net.azisaba.townia.migration

import com.palmergames.bukkit.towny.TownyUniverse
import com.palmergames.bukkit.towny.`object`.Town as TownyTown
import com.palmergames.bukkit.towny.`object`.TownyPermission
import com.palmergames.bukkit.towny.`object`.TownyPermission.ActionType
import com.palmergames.bukkit.towny.`object`.TownyPermission.PermLevel
import com.palmergames.bukkit.towny.`object`.TownBlock as TownyTownBlock
import net.azisaba.townia.Townia
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaJailCell
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.util.*
import java.util.logging.Level
import java.util.concurrent.TimeUnit
import kotlin.math.max

object TownyMigrator {
    private val allParts = setOf("towns", "nations", "residents", "townblocks", "jails", "outlaws", "relations")

    fun migrate(plugin: Townia, sender: CommandSender, requestedParts: Set<String> = emptySet()) {
        if (!plugin.server.pluginManager.isPluginEnabled("Towny")) {
            TownyFlatfileMigrator.migrate(plugin, sender, requestedParts)
            return
        }
        val parts = normalizeParts(requestedParts)

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            plugin.messageManager.sendMessage(sender, "admin.migration_start")
            plugin.messageManager.sendMessage(sender, "admin.migration-source", "source", "Towny API (${parts.joinToString(",")})")
            var towns = 0
            var nations = 0
            var residents = 0
            var plots = 0
            try {
                val townByResident = LinkedHashMap<UUID, UUID>()
                val rankByResident = LinkedHashMap<UUID, TownRank>()
                TownyUniverse.getInstance().towns.forEach { collectTownResidents(it, townByResident, rankByResident) }

                if (parts.contains("towns")) {
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "towns", "count", "0")
                for (tTown in TownyUniverse.getInstance().towns) {
                    val nationUuid = if (tTown.hasNation()) tTown.nation.uuid else null
                    var balance = 0.0
                    try {
                        balance = tTown.getAccount().getCachedBalance()
                    } catch (_: Exception) {
                    }

                    var spawnWorld: String? = null
                    var spawnX = 0.0
                    var spawnY = 0.0
                    var spawnZ = 0.0
                    var spawnYaw = 0f
                    var spawnPitch = 0f
                    if (tTown.hasSpawn()) {
                        spawnWorld = tTown.getSpawn().getWorld().name
                        spawnX = tTown.getSpawn().x
                        spawnY = tTown.getSpawn().y
                        spawnZ = tTown.getSpawn().z
                        spawnYaw = tTown.getSpawn().yaw
                        spawnPitch = tTown.getSpawn().pitch
                    } else if (tTown.hasHomeBlock()) {
                        try {
                            spawnWorld = tTown.homeBlock.world.name
                            spawnX = tTown.homeBlock.x * 16 + 8.5
                            spawnZ = tTown.homeBlock.z * 16 + 8.5
                            val bWorld = Bukkit.getWorld(spawnWorld)
                            if (bWorld != null) {
                                spawnY = (bWorld.getHighestBlockYAt(spawnX.toInt(), spawnZ.toInt()) + 1).toDouble()
                            } else {
                                spawnY = 64.0
                            }
                        } catch (_: Exception) {
                        }
                    }

                    val ourTown = Town(
                        tTown.uuid,
                        tTown.name,
                        tTown.mayor.uuid,
                        nationUuid,
                        balance,
                        tTown.maxTownBlocks,
                        tTown.bonusBlocks,
                        tTown.isPublic,
                        tTown.registered,
                        tTown.board,
                        tTown.taxes,
                        tTown.plotTax,
                        tTown.permissions.pvp,
                        tTown.permissions.mobs,
                        tTown.permissions.explosion,
                        tTown.permissions.fire,
                        spawnWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch
                    )

                    ourTown.isOpen = tTown.isOpen
                    applyTownyGovernmentPermissions(tTown.permissions, ourTown)
                    if (tTown.hasHomeBlock()) {
                        try {
                            ourTown.setHomeBlock(
                                tTown.homeBlock.world.name,
                                tTown.homeBlock.x,
                                tTown.homeBlock.z
                            )
                        } catch (_: Exception) {
                        }
                    }

                    plugin.databaseManager.saveTown(ourTown)
                    plugin.townManager.cacheTown(ourTown)
                    towns++
                }
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "towns", "count", towns.toString())
                }

                if (parts.contains("outlaws")) {
                    plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "outlaws", "count", "0")
                    val outlaws = migrateTownOutlaws(plugin)
                    plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "outlaws", "count", outlaws.toString())
                }

                if (parts.contains("nations")) {
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "nations", "count", "0")
                for (tNation in TownyUniverse.getInstance().nations) {
                    var balance = 0.0
                    try {
                        balance = tNation.getAccount().getCachedBalance()
                    } catch (_: Exception) {
                    }

                    val ourNation = Nation(
                        tNation.uuid,
                        tNation.name,
                        tNation.capital.uuid,
                        tNation.king.uuid,
                        balance,
                        tNation.board,
                        tNation.taxes
                    )

                    plugin.databaseManager.saveNation(ourNation)
                    plugin.nationManager.cacheNation(ourNation)
                    nations++
                }
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "nations", "count", nations.toString())
                }

                if (parts.contains("relations")) {
                    plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "relations", "count", "0")
                    val relations = migrateNationRelations(plugin)
                    plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "relations", "count", relations.toString())
                }

                if (parts.contains("residents")) {
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "residents", "count", "0")
                for (tRes in TownyUniverse.getInstance().residents) {
                    // Towny's resident pointer can be stale while a town is being loaded or
                    // repaired. The town's membership list is the authoritative side of this
                    // relation, so prefer the mapping collected from every Town first.
                    val townUuid = townByResident[tRes.uuid]
                        ?: runCatching { if (tRes.hasTown()) tRes.town.uuid else null }.getOrNull()
                    var rank: TownRank = TownRank.RESIDENT
                    if (rankByResident[tRes.uuid] == TownRank.MAYOR || tRes.isMayor) {
                        rank = TownRank.MAYOR
                    } else if (rankByResident[tRes.uuid] == TownRank.CO_MAYOR ||
                        tRes.hasTownRank("co-mayor") || tRes.hasTownRank("comayor") || tRes.hasTownRank("submayor")) {
                        rank = TownRank.CO_MAYOR
                    } else if (rankByResident[tRes.uuid] == TownRank.ASSISTANT || tRes.hasTownRank("assistant")) {
                        rank = TownRank.ASSISTANT
                    }

                    // Towny may still store a pre-upgrade UUID. For a player
                    // currently connected to this server, the Minecraft UUID
                    // is authoritative and lets the migration link them now.
                    val playerUuid = Bukkit.getPlayerExact(tRes.name)?.uniqueId ?: tRes.uuid

                    val player = TowniaPlayer(
                        playerUuid,
                        tRes.name,
                        townUuid,
                        rank,
                        tRes.lastOnline,
                        null
                    )
                    player.registeredAt = tRes.registered
                    applyTownyResidentPermissions(tRes.permissions, player)
                    if (tRes.isJailed) {
                        runCatching {
                            player.jailedTownUuid = tRes.jailTown.uuid
                            player.jailBail = tRes.jailBailCost
                            player.jailReleaseAt = if (tRes.jailHours > 0) {
                                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(tRes.jailHours.toLong())
                            } else {
                                0L
                            }
                        }
                    }

                    plugin.databaseManager.saveResident(player)
                    plugin.residentManager.cacheResident(player)
                    residents++
                }
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "residents", "count", residents.toString())
                }

                if (parts.contains("townblocks")) {
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "townblocks", "count", "0")
                for (tb in collectTownBlocks()) {
                    if (migrateTownBlock(plugin, tb)) plots++
                }
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "townblocks", "count", plots.toString())
                }

                if (parts.contains("jails")) {
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "jails", "count", "0")
                val jails = migrateTownJails(plugin)
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "jails", "count", jails.toString())
                }
                sendValidation(plugin, sender)

                plugin.messageManager.sendMessage(
                    sender, "admin.migration_success",
                    "towns", towns.toString(),
                    "nations", nations.toString(),
                    "residents", residents.toString(),
                    "plots", plots.toString()
                )
                plugin.logger.info("Towny Migration successful.")
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Migration failed", e)
                plugin.messageManager.sendMessage(
                    sender,
                    "admin.migration_failed",
                    "{0}",
                    "Migration encountered an error. Check console."
                )
            }
        })
    }

    private fun normalizeParts(requestedParts: Set<String>): Set<String> {
        if (requestedParts.isEmpty() || requestedParts.any { it == "all" || it == "*" }) return allParts
        val result = LinkedHashSet<String>()
        requestedParts.forEach { part ->
            when (part) {
                "plots", "plot", "townblock" -> result.add("townblocks")
                "town", "towns" -> result.add("towns")
                "nation", "nations" -> result.add("nations")
                "resident", "residents" -> result.add("residents")
                "jail", "jails" -> result.add("jails")
                "outlaw", "outlaws" -> result.add("outlaws")
                "relation", "relations", "allies", "enemies" -> result.add("relations")
                "data" -> result.addAll(allParts)
            }
        }
        return if (result.isEmpty()) allParts else result
    }

    private fun sendValidation(plugin: Townia, sender: CommandSender) {
        val townResidents = plugin.residentManager.allResidents.count { it.townUuid != null }
        val unresolvedTownResidents = plugin.residentManager.allResidents.count { it.townUuid != null && plugin.townManager.getTown(it.townUuid).isEmpty }
        val ownedPlots = plugin.residentManager.allResidents.sumOf { resident ->
            resident.uuid?.let { uuid -> plugin.plotManager.countPlotsByOwner(uuid) } ?: 0
        }
        plugin.messageManager.sendMessage(
            sender,
            "admin.migration-validation",
            "town_residents",
            townResidents.toString(),
            "unresolved_town_residents",
            unresolvedTownResidents.toString(),
            "owned_plots",
            ownedPlots.toString()
        )
    }

    private fun migrateTownOutlaws(plugin: Townia): Int {
        var count = 0
        for (tTown in TownyUniverse.getInstance().towns) {
            val town = plugin.townManager.getTown(tTown.uuid).orElse(null) ?: continue
            runCatching {
                tTown.getOutlaws().forEach { outlaw ->
                    plugin.databaseManager.addTownOutlaw(town.id!!, outlaw.uuid)
                    town.outlaws.add(outlaw.uuid)
                    count++
                }
            }
        }
        return count
    }

    private fun collectTownResidents(
        tTown: TownyTown,
        townByResident: MutableMap<UUID, UUID>,
        rankByResident: MutableMap<UUID, TownRank>
    ) {
        runCatching {
            tTown.getResidents().forEach { resident ->
                townByResident[resident.uuid] = tTown.uuid
                val rank = when {
                    tTown.mayor.uuid == resident.uuid -> TownRank.MAYOR
                    resident.hasTownRank("co-mayor") || resident.hasTownRank("comayor") || resident.hasTownRank("submayor") -> TownRank.CO_MAYOR
                    resident.hasTownRank("assistant") -> TownRank.ASSISTANT
                    else -> TownRank.RESIDENT
                }
                rankByResident[resident.uuid] = rank
            }
        }
    }

    private fun collectTownBlocks(): Collection<TownyTownBlock> {
        val blocks = LinkedHashMap<String, TownyTownBlock>()
        TownyUniverse.getInstance().townBlocks.values.forEach { block ->
            blocks[townBlockKey(block)] = block
        }
        TownyUniverse.getInstance().towns.forEach { town ->
            runCatching {
                town.getTownBlocks().forEach { block ->
                    blocks[townBlockKey(block)] = block
                }
            }
        }
        return blocks.values
    }

    private fun townBlockKey(block: TownyTownBlock): String {
        return "${block.world.name}:${block.x}:${block.z}"
    }

    private fun migrateTownBlock(plugin: Townia, tb: TownyTownBlock): Boolean {
        if (!tb.hasTown()) return false

        val townUuid = runCatching { tb.getTown().uuid }.getOrNull() ?: return false
        if (plugin.townManager.getTown(townUuid).isEmpty) return false
        val ownerUuid = runCatching { if (tb.hasResident()) tb.getResident().uuid else null }.getOrNull()

        var type: PlotType? = PlotType.DEFAULT
        try {
            var typeName = tb.type.name.uppercase(Locale.getDefault())
            if (typeName == "COMMERCIAL") typeName = "SHOP"
            if (typeName == "JAIL" || typeName == "WILDS") typeName = "DEFAULT"
            type = PlotType.valueOf(typeName)
        } catch (_: Exception) {
        }

        val forSale = tb.plotPrice >= 0
        val price = max(0.0, tb.plotPrice)
        val plot = Plot(
            tb.world.name,
            tb.x,
            tb.z,
            townUuid,
            ownerUuid,
            type,
            forSale,
            price,
            tb.name,
            tb.getPermissions().pvp,
            tb.getPermissions().mobs,
            tb.getPermissions().explosion,
            tb.getPermissions().fire
        )
        applyTownyPlotPermissions(tb.getPermissions(), plot)

        plugin.databaseManager.savePlot(plot)
        plugin.plotManager.cachePlot(plot)
        return true
    }

    private fun migrateTownJails(plugin: Townia): Int {
        var count = 0
        for (tTown in TownyUniverse.getInstance().towns) {
            val town = plugin.townManager.getTown(tTown.uuid).orElse(null) ?: continue
            runCatching {
                tTown.getJails().orEmpty().forEach { jail ->
                    jail.getJailCellLocations().forEachIndexed { index, location ->
                        val cell = TowniaJailCell(
                            0,
                            "jail-${jail.uuid}-${index + 1}",
                            location.world.name,
                            location.x,
                            location.y,
                            location.z,
                            location.yaw,
                            location.pitch
                        )
                        plugin.databaseManager.saveTownJailCell(town.id!!, cell)
                        town.jailCells.add(cell)
                        if (!town.hasJail()) {
                            town.setJail(location.world.name, location.x, location.y, location.z, location.yaw, location.pitch)
                            plugin.databaseManager.saveTown(town)
                            plugin.townManager.cacheTown(town)
                        }
                        count++
                    }
                }
            }
        }
        return count
    }

    private fun migrateNationRelations(plugin: Townia): Int {
        var count = 0
        for (tNation in TownyUniverse.getInstance().nations) {
            val nation = plugin.nationManager.getNation(tNation.uuid).orElse(null) ?: continue
            runCatching {
                tNation.getAllies().forEach { ally ->
                    nation.allies.add(ally.uuid)
                    plugin.databaseManager.addNationRelation(nation.id!!, ally.uuid, "ALLY")
                    count++
                }
                tNation.getEnemies().forEach { enemy ->
                    nation.enemies.add(enemy.uuid)
                    plugin.databaseManager.addNationRelation(nation.id!!, enemy.uuid, "ENEMY")
                    count++
                }
                plugin.nationManager.cacheNation(nation)
            }
        }
        return count
    }

    private fun applyTownyGovernmentPermissions(permission: TownyPermission, town: Town) {
        town.permsResident = permissionString(permission, PermLevel.RESIDENT)
        town.permsNation = permissionString(permission, PermLevel.NATION)
        town.permsAlly = permissionString(permission, PermLevel.ALLY)
        town.permsOutsider = permissionString(permission, PermLevel.OUTSIDER)
    }

    private fun applyTownyResidentPermissions(permission: TownyPermission, player: TowniaPlayer) {
        player.defaultPermsFriend = permissionString(permission, PermLevel.RESIDENT)
        player.defaultPermsResident = permissionString(permission, PermLevel.NATION)
        player.defaultPermsAlly = permissionString(permission, PermLevel.ALLY)
        player.defaultPermsOutsider = permissionString(permission, PermLevel.OUTSIDER)
    }

    private fun applyTownyPlotPermissions(permission: TownyPermission, plot: Plot) {
        plot.permsResident = permissionString(permission, PermLevel.RESIDENT)
        plot.permsNation = permissionString(permission, PermLevel.NATION)
        plot.permsAlly = permissionString(permission, PermLevel.ALLY)
        plot.permsOutsider = permissionString(permission, PermLevel.OUTSIDER)
    }

    private fun permissionString(permission: TownyPermission, level: PermLevel): String {
        val sb = StringBuilder()
        if (permission.getPerm(level, ActionType.BUILD)) sb.append('B')
        if (permission.getPerm(level, ActionType.DESTROY)) sb.append('D')
        if (permission.getPerm(level, ActionType.SWITCH)) sb.append('S')
        if (permission.getPerm(level, ActionType.ITEM_USE)) sb.append('I')
        return sb.toString()
    }
}
