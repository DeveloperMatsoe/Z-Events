package nl.eventcore.event;

import net.kyori.adventure.text.Component;
import nl.eventcore.EventCorePlugin;
import nl.eventcore.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import nl.eventcore.util.PlayerSnapshot;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Houdt bij welke events bestaan en welk event er nu draait (maximaal één tegelijk). */
public final class EventManager implements Listener {

    public record Result(boolean ok, Component message) {}

    private final EventCorePlugin plugin;
    private final Map<String, Supplier<GameEvent>> registry = new LinkedHashMap<>();
    private GameEvent active;
    private BukkitTask lobbyTimeout;

    public EventManager(EventCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    public void register(String id, Supplier<GameEvent> factory) {
        registry.put(id.toLowerCase(Locale.ROOT), factory);
    }

    public Set<String> ids() {
        return registry.keySet();
    }

    /** Weergavenaam van een event (bv. "TNT Tag"). */
    public String displayName(String id) {
        Supplier<GameEvent> factory = registry.get(id.toLowerCase(Locale.ROOT));
        return factory == null ? id : factory.get().getDisplayName();
    }

    /** Setup-opties van een event (bv. lobby, spawn, pos1, pos2). */
    public java.util.List<String> setupKeys(String id) {
        Supplier<GameEvent> factory = registry.get(id.toLowerCase(Locale.ROOT));
        return factory == null ? java.util.List.of() : factory.get().setupKeys();
    }

    /** Laat een event zelf een setup-optie afhandelen. false = EventCommand slaat gewoon een locatie op. */
    public boolean handleSetup(String id, Player player, String key, String[] args) {
        Supplier<GameEvent> factory = registry.get(id.toLowerCase(Locale.ROOT));
        return factory != null && factory.get().handleSetup(player, key, args);
    }

    /** Tab-aanvulling na een setup-optie (bv. /event setup elytrarace checkpoint <add|remove|...>). */
    public java.util.List<String> setupArgs(String id, String key) {
        Supplier<GameEvent> factory = registry.get(id.toLowerCase(Locale.ROOT));
        return factory == null ? java.util.List.of() : factory.get().setupArgs(key);
    }

    public Optional<GameEvent> active() {
        return Optional.ofNullable(active);
    }

    public Result start(String id) {
        Supplier<GameEvent> factory = registry.get(id.toLowerCase(Locale.ROOT));
        if (factory == null) return new Result(false, m().prefixed("event.unknown", "event", id));
        if (active != null) {
            return new Result(false, m().prefixed("event.already-active", "event", active.getDisplayName()));
        }

        GameEvent event = factory.get();
        Optional<Component> problem = event.validate();
        if (problem.isPresent()) return new Result(false, problem.get());

        active = event;
        try {
            event.open();
        } catch (RuntimeException ex) {
            // Vangnet: zonder dit blijft een half geopend event "actief" en kan er niets meer starten of stoppen.
            plugin.getLogger().log(Level.SEVERE, "Kon " + event.getDisplayName() + " niet openen", ex);
            if (event instanceof Listener listener) HandlerList.unregisterAll(listener);
            active = null;
            return new Result(false, m().prefixed("event.open-failed", "event", event.getDisplayName()));
        }
        scheduleLobbyTimeout(event);
        return new Result(true, m().prefixed("event.opened", "event", event.getDisplayName()));
    }

    public Result stop(String id) {
        if (active == null) return new Result(false, m().prefixed("event.not-running"));
        if (id != null && !active.getId().equalsIgnoreCase(id)) {
            return new Result(false, m().prefixed("event.other-running", "event", id, "active", active.getDisplayName()));
        }
        String name = active.getDisplayName();
        active.stop(true);
        return new Result(true, m().prefixed("event.stopped", "event", name));
    }

    /** Stopt het actieve event zonder melding (gebruikt bij afsluiten). */
    public void stopActive() {
        if (active != null) active.stop(true);
    }

    /** Wordt door GameEvent#stop aangeroepen als het event klaar is. */
    void finish(GameEvent event) {
        cancelLobbyTimeout();
        if (active == event) active = null;
    }

    /**
     * Sluit de lobby automatisch als er na X minuten nog te weinig spelers zijn.
     * Controleert elke 10 seconden (niet één keer): loopt op het moment van de timeout net de countdown
     * en valt die later terug naar de lobby, dan sluit de lobby alsnog.
     */
    private void scheduleLobbyTimeout(GameEvent event) {
        int minutes = plugin.getConfig().getInt("general.lobby-timeout-minutes", 10);
        if (minutes <= 0) return;
        long deadline = System.currentTimeMillis() + minutes * 60_000L;

        lobbyTimeout = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (active != event || event.getState() == EventState.RUNNING || event.getState() == EventState.ENDING) {
                cancelLobbyTimeout();   // het spel is begonnen of voorbij: niet meer nodig
                return;
            }
            if (event.getState() == EventState.LOBBY && System.currentTimeMillis() >= deadline) {
                cancelLobbyTimeout();
                Bukkit.broadcast(m().prefixed("event.lobby-timeout", "event", event.getDisplayName()));
                event.stop(false);
            }
        }, 200L, 200L);
    }

    private void cancelLobbyTimeout() {
        if (lobbyTimeout != null) {
            lobbyTimeout.cancel();
            lobbyTimeout = null;
        }
    }

    /** Na een crash midden in een event: de speler krijgt bij de volgende join zijn oude staat terug. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (!PlayerSnapshot.file(plugin.getDataFolder(), uuid).exists()) return;
        // Eén tick later: tijdens de join zelf kan een teleport nog door de server overschreven worden.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) recoverSnapshot(p);
        });
    }

    /** Zet een achtergebleven snapshot van schijf terug, als de speler nu niet in een event zit. */
    public void recoverSnapshot(Player p) {
        if (active != null && active.isParticipant(p)) return;
        File file = PlayerSnapshot.file(plugin.getDataFolder(), p.getUniqueId());
        if (!file.exists()) return;
        try {
            PlayerSnapshot.load(file).restore(p);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException ex) {
            // Bestand blijft staan zodat een admin het met de hand kan bekijken.
            plugin.getLogger().log(Level.SEVERE, "Kon de opgeslagen staat van " + p.getName()
                    + " niet terugzetten (" + file.getName() + ")", ex);
            return;
        }
        if (!file.delete()) plugin.getLogger().warning("Kon snapshotbestand niet verwijderen: " + file.getName());
        p.sendMessage(m().prefixed("event.recovered"));
        plugin.getLogger().info("Inventaris van " + p.getName() + " teruggezet na onverwacht afsluiten.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (active != null && active.isParticipant(p)) active.leave(p);
    }
}
