package nl.eventcore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.eventcore.EventCorePlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** Leest teksten uit messages.yml. Ontbrekende regels vallen terug op de standaard uit de jar. */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final EventCorePlugin plugin;
    private YamlConfiguration cfg = new YamlConfiguration();

    public Messages(EventCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        cfg = YamlConfiguration.loadConfiguration(file);

        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Kon standaardberichten niet laden", e);
        }
    }

    public String raw(String key) {
        return cfg.getString(key, "<red>[ontbrekend bericht: " + key + "]");
    }

    /** Bericht zonder prefix. Placeholders als naam, waarde, naam, waarde, ... */
    public Component get(String key, Object... placeholders) {
        return MM.deserialize(raw(key), resolvers(placeholders));
    }

    /** Bericht met prefix. */
    public Component prefixed(String key, Object... placeholders) {
        return MM.deserialize(cfg.getString("prefix", "") + raw(key), resolvers(placeholders));
    }

    /** Lijst met regels (zonder prefix). */
    public List<Component> list(String key) {
        List<Component> out = new ArrayList<>();
        for (String line : cfg.getStringList(key)) out.add(MM.deserialize(line));
        return out;
    }

    private static TagResolver[] resolvers(Object... kv) {
        List<TagResolver> list = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            list.add(Placeholder.unparsed(String.valueOf(kv[i]), String.valueOf(kv[i + 1])));
        }
        return list.toArray(new TagResolver[0]);
    }
}
