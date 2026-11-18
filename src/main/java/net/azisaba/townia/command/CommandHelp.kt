package net.azisaba.townia.command

import net.azisaba.townia.manager.MessageManager
import org.bukkit.command.CommandSender
import java.util.Locale

/**
 * Per-subcommand help shared by every Townia command.
 *
 * Each entry is a subcommand path such as `claim` or `set.perm`. Its text lives in the
 * language files under `cmdhelp.<command>.<path>`: `usage` (syntax), `desc` (one line) and
 * an optional `detail` (extra lines for `/<command> help <subcommand>`).
 */
object CommandHelp {
    private const val PAGE_SIZE = 10

    private val entries: Map<String, List<String>> = linkedMapOf(
        "town" to listOf(
            "info", "here", "list", "new", "join", "leave", "spawn", "online", "reslist", "ranklist",
            "claim", "unclaim",
            "outpost.list", "outpost.tp", "outpost.togglepublic", "outpost.rename",
            "invite", "kick", "rank.add", "rank.remove",
            "set.spawn", "set.homeblock", "set.name", "set.board", "set.taxes", "set.plotprice",
            "set.public", "set.private", "set.perm", "set.jail",
            "toggle", "deposit", "withdraw", "bankhistory",
            "outlaw", "outlawlist", "jail", "unjail", "jailcell",
            "map", "menu", "delete"
        ),
        "nation" to listOf(
            "info", "list", "new", "join", "leave", "invite", "kick", "spawn", "setspawn", "online",
            "deposit", "withdraw", "bankhistory",
            "set.board", "set.name", "set.taxes", "set.king", "set.capital", "set.title", "set.surname",
            "ally", "enemy", "toggle", "delete"
        ),
        "plot" to listOf(
            "info", "set.type", "set.name", "set.perm", "set.reset", "toggle",
            "forsale", "notforsale", "buy", "clear", "evict"
        ),
        "resident" to listOf(
            "info", "list", "plotlist", "set.about", "set.perm", "set.mode", "toggle", "friend", "spawn", "tax", "jail"
        ),
        "townia" to listOf("info", "map", "price", "time", "top", "reload", "debug"),
        "towniaadmin" to listOf(
            "reload", "bypass", "forceclaim", "forceunclaim", "deletetown", "deletenation", "givebonus",
            "setmayor", "addresident", "mergeresident", "dedupe", "migrate"
        ),
        "towniaworld" to listOf("list", "add", "remove"),
        "invite" to listOf("list", "accept", "decline"),
        "townconfig" to listOf("set")
    )

    private val aliases: Map<String, Map<String, String>> = mapOf(
        "town" to mapOf("add" to "invite", "outlow" to "outlaw", "outlowlist" to "outlawlist", "gui" to "menu"),
        "nation" to mapOf("add" to "invite"),
        "plot" to mapOf("here" to "info", "perm" to "info", "fs" to "forsale", "nfs" to "notforsale", "claim" to "buy")
    )

    /** Every command and its entry paths, in display order. */
    internal fun allEntries(): Map<String, List<String>> = entries

    /** The command being executed on this thread (`/<root> args...`). */
    private val currentCommand = ThreadLocal<Pair<String, List<String>>?>()

    /** Runs [block] with `/<root> args...` as the current command, for [sendInvalidArgsForCurrentCommand]. */
    fun <T> withCommand(root: String, args: Array<out String>, block: () -> T): T {
        val previous = currentCommand.get()
        currentCommand.set(root to args.toList())
        try {
            return block()
        } finally {
            currentCommand.set(previous)
        }
    }

    /**
     * Sends the usage of the subcommand being executed instead of a bare "invalid arguments".
     * Returns false outside a Townia command, so the caller sends the plain message.
     */
    fun sendInvalidArgsForCurrentCommand(mm: MessageManager, sender: CommandSender): Boolean {
        val context = currentCommand.get() ?: return false
        if (context.first !in entries) return false
        // Cleared while sending so the help output can never recurse back here.
        currentCommand.set(null)
        try {
            sendInvalidArgs(mm, sender, context.first, context.second)
        } finally {
            currentCommand.set(context)
        }
        return true
    }

    /**
     * `/<root> help [page|subcommand...]`. [rest] is everything after "help".
     */
    fun handleHelp(mm: MessageManager, sender: CommandSender, root: String, rest: List<String>) {
        val first = rest.firstOrNull()
        if (first == null) {
            sendList(mm, sender, root, 1)
            return
        }
        val page = first.toIntOrNull()
        if (page != null) {
            sendList(mm, sender, root, page)
            return
        }
        val matches = findEntries(root, rest)
        if (matches.isEmpty()) {
            mm.sendMessage(sender, "cmdhelp.unknown", "command", root, "sub", mm.miniMessage.escapeTags(rest.joinToString(" ")))
            return
        }
        for (id in matches) sendDetail(mm, sender, root, id)
    }

    /** `/<root> <unknown>`: names the bad subcommand and points to the list. */
    fun sendUnknown(mm: MessageManager, sender: CommandSender, root: String, sub: String) {
        mm.sendMessage(sender, "cmdhelp.unknown", "command", root, "sub", mm.miniMessage.escapeTags(sub))
    }

    fun sendList(mm: MessageManager, sender: CommandSender, root: String, requestedPage: Int) {
        val ids = entries[root] ?: return
        val maxPage = (ids.size + PAGE_SIZE - 1) / PAGE_SIZE
        val page = requestedPage.coerceIn(1, maxPage)
        mm.sendMessageWithoutPrefix(sender, "cmdhelp.header", "command", root, "page", page.toString(), "max", maxPage.toString())
        for (id in ids.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)) {
            mm.sendMessageWithoutPrefix(
                sender, "cmdhelp.entry",
                "usage", text(mm, sender, root, id, "usage"),
                "desc", text(mm, sender, root, id, "desc"),
                "suggest", suggestion(mm, sender, root, id),
                "detail_command", "/$root help ${id.replace('.', ' ')}"
            )
        }
        mm.sendMessageWithoutPrefix(sender, "cmdhelp.footer", "command", root)
        if (page < maxPage) {
            mm.sendMessageWithoutPrefix(sender, "cmdhelp.next-page", "command", root, "next", (page + 1).toString())
        }
    }

    private fun sendDetail(mm: MessageManager, sender: CommandSender, root: String, id: String) {
        mm.sendMessageWithoutPrefix(
            sender, "cmdhelp.detail",
            "usage", text(mm, sender, root, id, "usage"),
            "desc", text(mm, sender, root, id, "desc"),
            "suggest", suggestion(mm, sender, root, id)
        )
        val detail = mm.getRawLines(sender, "cmdhelp.$root.$id.detail") ?: return
        for (line in detail) {
            mm.sendMessageWithoutPrefix(sender, "cmdhelp.detail-line", "line", mm.miniMessage.escapeTags(line))
        }
    }

    /**
     * Replaces the bare "invalid arguments" error: names the subcommand the player
     * was typing and shows how it is used.
     */
    fun sendInvalidArgs(mm: MessageManager, sender: CommandSender, root: String, args: List<String>) {
        val matches = findEntries(root, args)
        if (matches.isEmpty()) {
            mm.sendMessage(sender, "cmdhelp.invalid-args", "command", root)
            return
        }
        mm.sendMessage(sender, "cmdhelp.invalid-args-usage")
        for (id in matches) {
            mm.sendMessageWithoutPrefix(
                sender, "cmdhelp.usage-line",
                "usage", text(mm, sender, root, id, "usage"),
                "desc", text(mm, sender, root, id, "desc"),
                "suggest", suggestion(mm, sender, root, id)
            )
        }
        val sub = if (matches.size == 1) matches.first().replace('.', ' ') else matches.first().substringBefore('.')
        mm.sendMessageWithoutPrefix(sender, "cmdhelp.see-detail", "command", root, "sub", sub)
    }

    /**
     * The entries [args] refer to: the longest entry path that is a prefix of [args],
     * or every entry under the first argument when the rest does not match.
     */
    internal fun findEntries(root: String, args: List<String>): List<String> {
        val ids = entries[root] ?: return emptyList()
        if (args.isEmpty()) return emptyList()
        val normalized = args.map { it.lowercase(Locale.ROOT) }.toMutableList()
        normalized[0] = aliases[root]?.get(normalized[0]) ?: normalized[0]

        for (length in normalized.size downTo 1) {
            val id = normalized.take(length).joinToString(".")
            if (id in ids) return listOf(id)
        }
        val head = normalized[0]
        val under = ids.filter { it.startsWith("$head.") }
        if (under.isNotEmpty()) return under
        // Commands whose first argument is a free value (e.g. /townconfig <key> <value>) have one entry.
        return if (ids.size == 1) ids else emptyList()
    }

    private fun text(mm: MessageManager, sender: CommandSender, root: String, id: String, field: String): String =
        mm.miniMessage.escapeTags(mm.getRawLines(sender, "cmdhelp.$root.$id.$field")?.joinToString(" ") ?: "/$root ${id.replace('.', ' ')}")

    /** Text put in the chat box when an entry is clicked: the usage up to its first argument. */
    private fun suggestion(mm: MessageManager, sender: CommandSender, root: String, id: String): String {
        val usage = mm.getRawLines(sender, "cmdhelp.$root.$id.usage")?.firstOrNull() ?: "/$root ${id.replace('.', ' ')}"
        val fixed = usage.takeWhile { it != '<' && it != '[' && it != '|' && it != '\'' }.trimEnd()
        return "$fixed "
    }
}
