package net.azisaba.townia.manager;

import net.azisaba.townia.Townia;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public class MessageManager {

    private final Townia plugin;
    private final Map<String, Map<String, Object>> allMessages = new HashMap<>();
    private String defaultLang;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MessageManager(Townia plugin) {
        this.plugin = plugin;
        loadAllMessages();
    }

    public void loadAllMessages() {
        allMessages.clear();
        defaultLang = plugin.getConfig().getString("default-language", "ja");

        File messagesDir = new File(plugin.getDataFolder(), "messages");
        if (!messagesDir.exists()) messagesDir.mkdirs();

        for (String code : List.of("en", "ja")) {
            File target = new File(messagesDir, "lang_" + code + ".yml");
            if (!target.exists()) {
                try (InputStream in = plugin.getResource("messages/lang_" + code + ".yml")) {
                    if (in != null) {
                        try (OutputStream out = new FileOutputStream(target)) {
                            in.transferTo(out);
                        }
                    }
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to extract lang_" + code + ".yml", e);
                }
            }
        }

        File[] langFiles = messagesDir.listFiles((dir, name) -> name.matches("lang_\\w+\\.yml"));
        if (langFiles == null) return;

        for (File langFile : langFiles) {
            String langCode = langFile.getName().replace("lang_", "").replace(".yml", "");
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(langFile), StandardCharsets.UTF_8)) {
                FileConfiguration cfg = YamlConfiguration.loadConfiguration(reader);
                Map<String, Object> messages = new LinkedHashMap<>();
                for (String key : cfg.getKeys(true)) {
                    if (!cfg.isConfigurationSection(key)) {
                        messages.put(key, cfg.get(key));
                    }
                }
                allMessages.put(langCode, messages);
                plugin.getLogger().info("Loaded language file: " + langFile.getName() + " (" + messages.size() + " keys)");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load " + langFile.getName(), e);
            }
        }
    }

    public void sendMessage(CommandSender sender, String key, String... replacements) {
        String lang = getLang(sender);
        Object messageObject = getMessageObject(lang, key);

        List<?> lines;
        if (messageObject instanceof List<?> l) {
            lines = l;
        } else {
            lines = Collections.singletonList(messageObject);
        }

        String prefix = plugin.getTowniaConfig().getPrefix();
        for (Object line : lines) {
            String raw = prefix + replacePlaceholders(String.valueOf(line), replacements);
            sender.sendMessage(miniMessage.deserialize(raw));
        }
    }

    public void sendActionBar(Player player, String key, String... replacements) {
        String lang = getLang(player);
        Object messageObject = getMessageObject(lang, key);

        String raw;
        if (messageObject instanceof List<?> l && !l.isEmpty()) {
            raw = String.valueOf(l.get(0));
        } else {
            raw = String.valueOf(messageObject);
        }

        raw = replacePlaceholders(raw, replacements);
        player.sendActionBar(miniMessage.deserialize(raw));
    }

    public String getPlainMessage(CommandSender sender, String key, String... replacements) {
        String lang = getLang(sender);
        Object obj = getMessageObject(lang, key);
        String raw;
        if (obj instanceof List<?> list && !list.isEmpty()) {
            raw = String.valueOf(list.getFirst());
        } else {
            raw = String.valueOf(obj);
        }
        raw = replacePlaceholders(raw, replacements);
        Component component = miniMessage.deserialize(raw);
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private String getLang(CommandSender sender) {
        if (sender instanceof Player player) {
            String clientLang = player.locale().getLanguage().toLowerCase();
            if (allMessages.containsKey(clientLang)) return clientLang;
        }
        return defaultLang;
    }

    private Object getMessageObject(String lang, String key) {
        Map<String, Object> messages = allMessages.getOrDefault(lang, allMessages.get(defaultLang));
        if (messages == null) return "<red>No language file loaded for: " + lang;
        return messages.getOrDefault(key, "<red>Missing message key: " + key);
    }

    private String replacePlaceholders(String text, String... replacements) {
        if (replacements.length % 2 != 0) {
            throw new IllegalArgumentException("Replacements must be in pairs (key, value, key, value, …)");
        }
        for (int i = 0; i < replacements.length; i += 2) {
            String key = replacements[i];
            if (!key.startsWith("{") && !key.endsWith("}")) {
                key = "{" + key + "}";
            }
            text = text.replace(key, replacements[i + 1]);
        }
        return text;
    }
}