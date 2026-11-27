package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Town
import net.azisaba.townia.permission.Perm
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.logging.Level

/**
 * Towny's rules around /town spawn and /nation spawn: which kinds of travel are open,
 * where it may be used from, the cooldown, the fare and the warm-up.
 * With nothing configured every check passes and the player is teleported at once.
 */
object SpawnTravel {

    /** Whose spawn the player is going to, seen from the player. */
    enum class Kind { TOWN, NATION, ALLY, PUBLIC }

    /**
     * Sends [player] to [destination], or tells them why not. [arriving] runs right before the
     * teleport and is where the caller sends its "teleporting to ..." message.
     * [fareTown] receives the fare when fares go to the town.
     */
    fun go(
        plugin: Townia, player: Player, kind: Kind, fareTown: Town?, destination: Location,
        ownFare: Double = fareTown?.spawnCost ?: -1.0, arriving: () -> Unit
    ) {
        val rules = plugin.towniaConfig.rules
        if (Perm.isAdmin(player)) {
            arriving()
            player.teleport(destination)
            return
        }

        val open = when (kind) {
            Kind.TOWN -> true
            Kind.NATION -> rules.allowSpawnTravelNation
            Kind.ALLY -> rules.allowSpawnTravelAlly
            Kind.PUBLIC -> rules.allowSpawnTravel
        }
        if (!open) {
            plugin.messageManager.sendMessage(player, "spawn.travel-disabled")
            return
        }
        if (rules.preventSpawnIn.isNotEmpty() && isPreventedHere(plugin, player, rules.preventSpawnIn)) {
            plugin.messageManager.sendMessage(player, "spawn.prevented-here")
            return
        }

        val cooldownKey = "spawn:" + player.uniqueId
        val wait = Cooldowns.remaining(cooldownKey, rules.spawnCooldownSeconds)
        if (wait > 0) {
            plugin.messageManager.sendMessage(player, "error.cooldown", "seconds", wait.toString())
            return
        }

        val serverFare = when (kind) {
            Kind.TOWN -> rules.spawnPriceTown
            Kind.NATION -> rules.spawnPriceNation
            Kind.ALLY -> rules.spawnPriceAlly
            Kind.PUBLIC -> rules.spawnPricePublic
        }
        // Towny: a town (or nation) may ask visitors for less than the server's price, never more.
        val fare = if (kind != Kind.TOWN && ownFare >= 0.0) minOf(ownFare, serverFare) else serverFare
        val economy = plugin.economy
        if (fare > 0.0 && economy != null) {
            if (!economy.has(player, fare) || !economy.withdrawPlayer(player, fare).transactionSuccess()) {
                plugin.messageManager.sendMessage(player, "error.insufficient-funds", "amount", economy.format(fare))
                return
            }
            if (rules.spawnPaidToTown && fareTown?.id != null) {
                try {
                    plugin.townManager.addBalance(fareTown.id, fare)
                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "Could not credit a spawn fare to ${fareTown.name}", e)
                }
            }
            plugin.messageManager.sendMessage(player, "spawn.fare-paid", "amount", economy.format(fare))
        }

        Cooldowns.start(cooldownKey)
        val warmup = rules.spawnWarmupSeconds
        if (warmup <= 0) {
            arriving()
            player.teleport(destination)
            return
        }
        plugin.messageManager.sendMessage(player, "spawn.warmup", "seconds", warmup.toString())
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            arriving()
            player.teleport(destination)
        }, warmup * 20L)
    }

    private fun isPreventedHere(plugin: Townia, player: Player, zones: Set<String>): Boolean {
        val here = plugin.plotManager.getPlotAt(player.location)
        if (here == null) return "unclaimed" in zones && plugin.towniaConfig.isWorldAllowed(player.world.name)
        val town = plugin.townManager.getTown(here.townUuid).orElse(null) ?: return false
        if ("outlaw" in zones && town.outlaws.contains(player.uniqueId)) return true
        if ("enemy" in zones) {
            val ownTown = plugin.residentManager.getResident(player.uniqueId).orElse(null)?.townUuid
                ?.let { plugin.townManager.getTown(it).orElse(null) }
            val ownNation = ownTown?.nationUuid?.let { plugin.nationManager.getNation(it).orElse(null) }
            val hereNation = town.nationUuid?.let { plugin.nationManager.getNation(it).orElse(null) }
            if (ownNation != null && hereNation != null &&
                (ownNation.enemies.contains(hereNation.id) || hereNation.enemies.contains(ownNation.id))
            ) return true
        }
        return false
    }
}
