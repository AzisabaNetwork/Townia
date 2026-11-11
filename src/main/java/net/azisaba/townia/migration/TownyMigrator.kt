package net.azisaba.townia.migration

import com.palmergames.bukkit.towny.TownyUniverse
import com.palmergames.bukkit.towny.`object`.Town as TownyTown
import com.palmergames.bukkit.towny.`object`.TownyPermission
import com.palmergames.bukkit.towny.`object`.TownyPermission.ActionType
import com.palmergames.bukkit.towny.`object`.TownyPermission.PermLevel
import com.palmergames.bukkit.towny.`object`.TownBlock as TownyTownBlock
import net.azisaba.townia.Townia
import net.azisaba.townia.util.ChunkKey
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaJailCell
import net.azisaba.townia.data.TowniaOutpost
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
    private val skipProfileLookupOptions = setOf("skip-profiles", "skip-profile-lookup", "ignore-missing-profiles")

    fun migrate(plugin: Townia, sender: CommandSender, requestedParts: Set<String> = emptySet()) {
        if (!plugin.server.pluginManager.isPluginEnabled("Towny")) {
            TownyFlatfileMigrator.migrate(plugin, sender, requestedParts)
            return
        }
        val parts = normalizeParts(requestedParts)
        val skipProfileLookup = requestedParts.any { it in skipProfileLookupOptions }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            plugin.messageManager.sendMessage(sender, "admin.migration_start")
            val source = "Towny API (${parts.joinToString(",")})" +
                if (skipProfileLookup) " [profile lookup skipped]" else ""
            plugin.messageManager.sendMessage(sender, "admin.migration-source", "source", source)
            var towns = 0
            var nations = 0
            var residents = 0
            var plots = 0
            var outposts = 0
            try {
                val townByResident = LinkedHashMap<UUID, UUID>()
                val rankByResident = LinkedHashMap<UUID, TownRank>()
                TownyUniverse.getInstance().towns.forEach { collectTownResidents(it, townByResident, rankByResident) }
                val currentUuidByTownyUuid = TownyUniverse.getInstance().residents.associate { resident ->
                    resident.uuid to if (skipProfileLookup) resident.uuid
                    else resolveCurrentMinecraftUuid(resident.name, resident.uuid)
                }
                val reconciledUuids = currentUuidByTownyUuid.count { (townyUuid, currentUuid) -> townyUuid != currentUuid }
                if (parts.contains("residents")) {
                    plugin.messageManager.sendMessage(
                        sender,
                        "admin.migration-progress",
                        "stage",
                        "resident UUID reconciliation",
                        "count",
                        reconciledUuids.toString()
                    )
                }

                if (parts.contains("towns")) {
                MigrationSupport.progress(plugin, sender, "towns", "0")
                for (tTown in TownyUniverse.getInstance().towns) {
                    val nationUuid = if (tTown.hasNation()) tTown.nation.uuid else null
                    val balance = resolveTownBalance(plugin, tTown)

                    var spawnWorld: String? = null
                    var spawnX = 0.0
                    var spawnY = 0.0
                    var spawnZ = 0.0
                    var spawnYaw = 0f
                    var spawnPitch = 0f
                    val townSpawn = if (tTown.hasSpawn()) tTown.spawnPosition() else null
                    if (townSpawn != null) {
                        // Read Towny's stored Position, not getSpawn(): the Location it builds
                        // has a null world when that world is not loaded right now.
                        spawnWorld = townSpawn.world().name
                        spawnX = townSpawn.x()
                        spawnY = townSpawn.y()
                        spawnZ = townSpawn.z()
                        spawnYaw = townSpawn.yaw()
                        spawnPitch = townSpawn.pitch()
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
                        currentUuidByTownyUuid[tTown.mayor.uuid] ?: tTown.mayor.uuid,
                        nationUuid,
                        balance,
                        tTown.maxTownBlocks,
                        tTown.bonusBlocks + tTown.purchasedBlocks,
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
                MigrationSupport.progress(plugin, sender, "towns", towns.toString())
                }

                if (parts.contains("outlaws")) {
                    MigrationSupport.progress(plugin, sender, "outlaws", "0")
                    val outlaws = migrateTownOutlaws(plugin, currentUuidByTownyUuid)
                    MigrationSupport.progress(plugin, sender, "outlaws", outlaws.toString())
                }

                if (parts.contains("nations")) {
                MigrationSupport.progress(plugin, sender, "nations", "0")
                for (tNation in TownyUniverse.getInstance().nations) {
                    val balance = resolveNationBalance(plugin, tNation)

                    var spawnWorld: String? = null
                    var spawnX = 0.0
                    var spawnY = 0.0
                    var spawnZ = 0.0
                    var spawnYaw = 0f
                    var spawnPitch = 0f
                    // Read Towny's stored Position, not getSpawn(): the Location it builds has
                    // a null world when that world is not loaded, which aborted the migration.
                    val nationSpawn = tNation.spawnPosition()
                    if (nationSpawn != null) {
                        spawnWorld = nationSpawn.world().name
                        spawnX = nationSpawn.x()
                        spawnY = nationSpawn.y()
                        spawnZ = nationSpawn.z()
                        spawnYaw = nationSpawn.yaw()
                        spawnPitch = nationSpawn.pitch()
                    } else {
                        plugin.logger.warning("Towny API: nation ${tNation.name} has no spawn set in Towny.")
                    }

                    val ourNation = Nation(
                        tNation.uuid,
                        tNation.name,
                        if (tNation.hasCapital()) tNation.capital.uuid else null,
                        currentUuidByTownyUuid[tNation.king.uuid] ?: tNation.king.uuid,
                        balance,
                        tNation.board,
                        tNation.taxes,
                        spawnWorld,
                        spawnX,
                        spawnY,
                        spawnZ,
                        spawnYaw,
                        spawnPitch
                    )
                    ourNation.isNeutral = tNation.isNeutral

                    plugin.databaseManager.saveNation(ourNation)
                    plugin.nationManager.cacheNation(ourNation)
                    nations++
                }
                MigrationSupport.progress(plugin, sender, "nations", nations.toString())
                }

                if (parts.contains("relations")) {
                    MigrationSupport.progress(plugin, sender, "relations", "0")
                    val relations = migrateNationRelations(plugin)
                    MigrationSupport.progress(plugin, sender, "relations", relations.toString())
                }

                if (parts.contains("residents")) {
                MigrationSupport.progress(plugin, sender, "residents", "0")
                val friendPairs = LinkedHashMap<UUID, List<UUID>>()
                val residentsToMigrate = pickOneResidentPerPlayer(plugin, currentUuidByTownyUuid, townByResident, rankByResident)
                for (tRes in residentsToMigrate) {
                    // Towny's resident pointer can be stale while a town is being loaded or
                    // repaired. The town's membership list is the authoritative side of this
                    // relation, so prefer the mapping collected from every Town first.
                    val townUuid = townByResident[tRes.uuid]
                        ?: runCatching { if (tRes.hasTown()) tRes.town.uuid else null }.getOrNull()
                    var rank: TownRank = TownRank.RESIDENT
                    if (rankByResident[tRes.uuid] == TownRank.MAYOR || tRes.isMayor) {
                        rank = TownRank.MAYOR
                    } else if (rankByResident[tRes.uuid] == TownRank.SUB_MAYOR ||
                        tRes.hasTownRank("co-mayor") || tRes.hasTownRank("comayor") || tRes.hasTownRank("submayor")) {
                        rank = TownRank.SUB_MAYOR
                    } else if (rankByResident[tRes.uuid] == TownRank.ASSISTANT || tRes.hasTownRank("assistant")) {
                        rank = TownRank.ASSISTANT
                    }

                    val playerUuid = currentUuidByTownyUuid[tRes.uuid] ?: tRes.uuid

                    runCatching {
                        val title = tRes.title
                        val surname = tRes.surname
                        if (!title.isNullOrBlank() || !surname.isNullOrBlank()) {
                            if (tRes.hasTown() && tRes.town.hasNation()) {
                                val nOpt = plugin.nationManager.getNation(tRes.town.nation.uuid)
                                if (nOpt.isPresent) {
                                    val nation = nOpt.get()
                                    if (!title.isNullOrBlank()) nation.setTitle(playerUuid, title)
                                    if (!surname.isNullOrBlank()) nation.setSurname(playerUuid, surname)
                                    plugin.databaseManager.saveNationTitles(nation)
                                }
                            }
                        }
                    }

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
                    runCatching {
                        friendPairs[playerUuid] = tRes.friends.map { currentUuidByTownyUuid[it.uuid] ?: it.uuid }
                    }
                    residents++
                }
                // Friends reference other residents (foreign key), so link them once every resident exists.
                var friendLinks = 0
                friendPairs.forEach { (ownerUuid, friendUuids) ->
                    val owner = plugin.residentManager.getResident(ownerUuid).orElse(null) ?: return@forEach
                    friendUuids.distinct().forEach { friendUuid ->
                        if (friendUuid == ownerUuid) return@forEach
                        if (plugin.residentManager.getResident(friendUuid).isEmpty) return@forEach
                        runCatching {
                            plugin.databaseManager.addFriend(ownerUuid, friendUuid)
                            if (owner.friends?.contains(friendUuid.toString()) != true) owner.friends?.add(friendUuid.toString())
                            friendLinks++
                        }
                    }
                }
                MigrationSupport.progress(plugin, sender, "friends", friendLinks.toString())
                MigrationSupport.progress(plugin, sender, "residents", residents.toString())
                }

                if (parts.contains("townblocks")) {
                MigrationSupport.progress(plugin, sender, "townblocks", "0")
                val missingOnly = requestedParts.contains(MigrationSupport.MISSING_ONLY)
                val seenChunks = HashSet<ChunkKey>()
                for (tb in collectTownBlocks()) {
                    val key = runCatching { ChunkKey(tb.world.name, tb.x, tb.z) }.getOrNull() ?: continue
                    seenChunks.add(key)
                    if (missingOnly && plugin.plotManager.isClaimed(key.world, key.x, key.z)) continue
                    if (migrateTownBlock(plugin, tb, currentUuidByTownyUuid)) plots++
                }
                if (requestedParts.contains(MigrationSupport.PRUNE)) {
                    val pruned = PlotPruner.prune(plugin, seenChunks)
                    MigrationSupport.progress(plugin, sender, "pruned plots", pruned.toString())
                }
                MigrationSupport.progress(plugin, sender, "townblocks", plots.toString())
                }

                if (parts.contains("towns") || parts.contains("townblocks")) {
                    MigrationSupport.progress(plugin, sender, "outposts", "0")
                    outposts = migrateTownOutposts(plugin)
                    MigrationSupport.progress(plugin, sender, "outposts", outposts.toString())
                }

                if (parts.contains("jails")) {
                MigrationSupport.progress(plugin, sender, "jails", "0")
                val jails = migrateTownJails(plugin)
                MigrationSupport.progress(plugin, sender, "jails", jails.toString())
                }
                plugin.residentManager.repairMayors()
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

    /**
     * Towny can hold the same player twice (an old-UUID record next to the
     * current one). Both map to the same Townia UUID, and saving both would let
     * iteration order decide which one survives - possibly a town-less record
     * overwriting the town member. Keep the most meaningful record per player.
     */
    private fun pickOneResidentPerPlayer(
        plugin: Townia,
        currentUuidByTownyUuid: Map<UUID, UUID>,
        townByResident: Map<UUID, UUID>,
        rankByResident: Map<UUID, TownRank>
    ): List<com.palmergames.bukkit.towny.`object`.Resident> {
        fun hasTown(res: com.palmergames.bukkit.towny.`object`.Resident): Boolean =
            townByResident[res.uuid] != null || runCatching { res.hasTown() }.getOrDefault(false)
        val preference = compareBy<com.palmergames.bukkit.towny.`object`.Resident>(
            { hasTown(it) },
            { (rankByResident[it.uuid] ?: TownRank.RESIDENT).level },
            { it.lastOnline }
        )
        return TownyUniverse.getInstance().residents
            .groupBy { currentUuidByTownyUuid[it.uuid] ?: it.uuid }
            .map { (playerUuid, group) ->
                val chosen = group.maxWith(preference)
                if (group.size > 1) {
                    plugin.logger.warning(
                        "Towny has ${group.size} residents for player $playerUuid " +
                            "(${group.joinToString { "${it.name}/${it.uuid}" }}); migrating ${chosen.name}/${chosen.uuid}."
                    )
                }
                chosen
            }
    }

    private fun migrateTownOutlaws(plugin: Townia, currentUuidByTownyUuid: Map<UUID, UUID>): Int {
        var count = 0
        for (tTown in TownyUniverse.getInstance().towns) {
            val town = plugin.townManager.getTown(tTown.uuid).orElse(null) ?: continue
            runCatching {
                tTown.getOutlaws().forEach { outlaw ->
                    // Same UUID mapping as residents, or the ban would not apply to the joining player.
                    val outlawUuid = currentUuidByTownyUuid[outlaw.uuid] ?: outlaw.uuid
                    plugin.databaseManager.addTownOutlaw(town.id!!, outlawUuid)
                    town.outlaws.add(outlawUuid)
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
                    resident.hasTownRank("co-mayor") || resident.hasTownRank("comayor") || resident.hasTownRank("submayor") -> TownRank.SUB_MAYOR
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

    private fun migrateTownBlock(
        plugin: Townia,
        tb: TownyTownBlock,
        currentUuidByTownyUuid: Map<UUID, UUID>
    ): Boolean {
        if (!tb.hasTown()) return false

        val townUuid = runCatching { tb.getTown().uuid }.getOrNull() ?: return false
        if (plugin.townManager.getTown(townUuid).isEmpty) return false
        val ownerUuid = runCatching {
            if (tb.hasResident()) {
                val owner = tb.getResident()
                currentUuidByTownyUuid[owner.uuid] ?: owner.uuid
            } else {
                null
            }
        }.getOrNull()

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

    /** Restores Towny's outpost spawn locations and their claimed-plot marker. */
    private fun migrateTownOutposts(plugin: Townia): Int {
        var count = 0
        for (townyTown in TownyUniverse.getInstance().towns) {
            val town = plugin.townManager.getTown(townyTown.uuid).orElse(null) ?: continue
            runCatching {
                // Re-running migration must retain any already-saved Townia outposts.
                plugin.databaseManager.loadTownOutposts(town)
                townyTown.allOutpostSpawns.forEach { location ->
                    val exists = town.outposts.filterNotNull().any { outpost ->
                        outpost.world == location.world.name &&
                            outpost.x == location.x && outpost.y == location.y && outpost.z == location.z
                    }
                    if (!exists) {
                        plugin.townManager.addTownOutpost(
                            town.id,
                            TowniaOutpost(
                                0,
                                location.world.name,
                                location.x,
                                location.y,
                                location.z,
                                location.yaw,
                                location.pitch,
                                false,
                                null
                            )
                        )
                        count++
                    }

                    val plot = plugin.plotManager.getPlot(
                        location.world.name,
                        location.blockX shr 4,
                        location.blockZ shr 4
                    ).orElse(null)
                    if (plot != null && !plot.isOutpost) {
                        plot.isOutpost = true
                        plugin.databaseManager.savePlot(plot)
                        plugin.plotManager.cachePlot(plot)
                    }
                }
            }.onFailure { error ->
                plugin.logger.log(Level.WARNING, "Failed to migrate outposts for ${townyTown.name}", error)
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
        // On resident-owned plots Towny's "resident" level is what the owner's friends get.
        if (plot.ownerUuid != null) plot.permsFriend = permissionString(permission, PermLevel.RESIDENT)
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

    /**
     * Only use Bukkit's name lookup when the server has seen the player. For
     * unknown names it can synthesize an offline UUID, which must not replace
     * a valid legacy Towny UUID before that player has joined.
     */
    private fun resolveCurrentMinecraftUuid(name: String, townyUuid: UUID): UUID {
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId }
        // getOfflinePlayerIfCached never asks Mojang; getOfflinePlayer(name) did, once per resident.
        val knownPlayer = Bukkit.getOfflinePlayerIfCached(name) ?: return townyUuid
        return if (knownPlayer.hasPlayedBefore()) knownPlayer.uniqueId else townyUuid
    }

    @Suppress("DEPRECATION")
    private fun resolveTownBalance(plugin: Townia, tTown: TownyTown): Double {
        var balance = 0.0
        runCatching {
            balance = tTown.getAccount().getHoldingBalance(true)
        }
        if (balance > 0.0) return balance

        if (plugin.hasEconomy()) {
            val eco = plugin.economy!!
            val candidates = listOf("town-${tTown.name}", "town-${tTown.uuid}", tTown.name)
            for (name in candidates) {
                runCatching {
                    if (eco.hasAccount(name)) {
                        val bal = eco.getBalance(name)
                        if (bal > 0.0) return bal
                    }
                }
            }
        }
        return balance
    }

    @Suppress("DEPRECATION")
    private fun resolveNationBalance(plugin: Townia, tNation: com.palmergames.bukkit.towny.`object`.Nation): Double {
        var balance = 0.0
        runCatching {
            balance = tNation.getAccount().getHoldingBalance(true)
        }
        if (balance > 0.0) return balance

        if (plugin.hasEconomy()) {
            val eco = plugin.economy!!
            val candidates = listOf("nation-${tNation.name}", "nation-${tNation.uuid}", tNation.name)
            for (name in candidates) {
                runCatching {
                    if (eco.hasAccount(name)) {
                        val bal = eco.getBalance(name)
                        if (bal > 0.0) return bal
                    }
                }
            }
        }
        return balance
    }
}
