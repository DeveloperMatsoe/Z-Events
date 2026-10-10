package nl.eventcore.event;

import net.kyori.adventure.text.Component;
import nl.eventcore.EventCorePlugin;
import nl.eventcore.util.PlayerSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Basis voor ieder event. Een nieuw event (Spleef, TNT Run, ...) extend deze klasse
 * en vult alleen de abstracte methodes in; join/leave/herstel regelt deze klasse.
 */
public abstract class GameEvent {

    protected final EventCorePlugin plugin;
    private final String id;
    private final String displayName;

    protected final Set<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    protected EventState state = EventState.IDLE;

    protected GameEvent(EventCorePlugin plugin, String id, String displayName) {
        this.plugin = plugin;
        this.id = id;
        this.displayName = displayName;
    }

    // ---------- door het event zelf in te vullen ----------

    /** Controleert of het event gestart kan worden (bv. arena ingesteld). Leeg = alles ok. */
    public abstract Optional<Component> validate();

    /** Opent de lobby. Zet de state op LOBBY. */
    protected abstract void onOpen();

    /** Speler is toegevoegd (inventaris is al opgeslagen en geleegd). */
    protected abstract void onJoin(Player player);

    /** Speler is weg (inventaris is al teruggezet). */
    protected abstract void onLeave(Player player);

    /** Opruimen: taken stoppen, listeners afmelden, enz. Spelers worden daarna hersteld. */
    protected abstract void onStop(boolean forced);

    /** Opties voor /event setup <event> <optie>. Standaard lobby en spawn; events mogen er meer toevoegen. */
    public List<String> setupKeys() {
        return List.of("lobby", "spawn");
    }

    /**
     * Eigen afhandeling van /event setup <event> <optie> [args], bv. voor een lijst checkpoints.
     * Geef true terug als het event de optie zelf heeft afgehandeld; anders slaat
     * EventCommand je huidige positie op onder <event>.<optie>.
     */
    public boolean handleSetup(Player player, String key, String[] args) {
        return false;
    }

    /** Tab-aanvulling voor het argument na een setup-optie (bv. add, remove, list). */
    public List<String> setupArgs(String key) {
        return List.of();
    }

    // ---------- levenscyclus ----------

    public final void open() {
        onOpen();
    }

    public final boolean join(Player player) {
        if (state != EventState.LOBBY && state != EventState.COUNTDOWN) return false;
        if (participants.contains(player.getUniqueId())) return false;

        participants.add(player.getUniqueId());
        PlayerSnapshot snapshot = PlayerSnapshot.of(player);
        snapshots.put(player.getUniqueId(), snapshot);
        saveSnapshot(player.getUniqueId(), snapshot);
        PlayerSnapshot.prepare(player);
        onJoin(player);
        return true;
    }

    public final void leave(Player player) {
        if (!participants.remove(player.getUniqueId())) return;
        PlayerSnapshot snapshot = snapshots.remove(player.getUniqueId());
        if (snapshot != null) {
            snapshot.restore(player);
            deleteSnapshot(player.getUniqueId());
        }
        onLeave(player);
    }

    public final void stop(boolean forced) {
        if (state == EventState.ENDING || state == EventState.IDLE) return;
        state = EventState.ENDING;
        onStop(forced);

        for (UUID uuid : new ArrayList<>(participants)) {
            Player p = Bukkit.getPlayer(uuid);
            PlayerSnapshot snapshot = snapshots.remove(uuid);
            if (p != null && snapshot != null) {
                snapshot.restore(p);
                deleteSnapshot(uuid);
            }
            // Speler niet online: het bestand blijft staan en wordt bij de volgende join teruggezet.
        }
        participants.clear();
        snapshots.clear();
        state = EventState.IDLE;
        plugin.getEventManager().finish(this);
    }

    // ---------- opslag tegen crashes ----------

    /*
     * Bewust synchroon: het bestand moet er staan voordat de inventaris geleegd wordt, en mag pas weg
     * nadat alles is teruggezet. Een async schrijfactie zou na het verwijderen kunnen landen en dan bij
     * de volgende join een oude inventaris terugzetten. Het is één klein bestand per speler.
     */
    private void saveSnapshot(UUID uuid, PlayerSnapshot snapshot) {
        File file = PlayerSnapshot.file(plugin.getDataFolder(), uuid);
        try {
            File dir = file.getParentFile();
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Kon map " + dir + " niet aanmaken");
            if (file.exists()) {
                // Een oud bestand dat niet teruggezet kon worden: niet overschrijven, apart bewaren voor een admin.
                File old = new File(dir, uuid + "-" + System.currentTimeMillis() + ".yml.old");
                if (file.renameTo(old)) plugin.getLogger().warning("Oud snapshotbestand bewaard als " + old.getName());
            }
            snapshot.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Kon snapshot van " + uuid
                    + " niet opslaan; bij een crash is de inventaris niet terug te zetten.", e);
        }
    }

    private void deleteSnapshot(UUID uuid) {
        File file = PlayerSnapshot.file(plugin.getDataFolder(), uuid);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Kon snapshotbestand niet verwijderen: " + file.getName());
        }
    }

    /** Waar een FloorArena van dit event zijn backup schrijft (zie FloorArena#takeSnapshot). */
    protected final File arenaBackupFile() {
        return new File(plugin.getDataFolder(), "recovery/arena-" + id + ".txt");
    }

    // ---------- hulpmethodes ----------

    public final void broadcastToParticipants(Component message) {
        for (Player p : players()) p.sendMessage(message);
    }

    public final List<Player> players() {
        List<Player> list = new ArrayList<>();
        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) list.add(p);
        }
        return list;
    }

    /** Wie er iets krijgt bij /event give all. Standaard iedereen in het event; events kunnen toeschouwers overslaan. */
    public List<Player> giveTargets() {
        return players();
    }

    public final boolean isParticipant(Player player) {
        return participants.contains(player.getUniqueId());
    }

    /** Vuurwerk voor de winnaar. */
    protected final void celebrate(Location loc) {
        Firework fw = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.ORANGE, Color.RED)
                .withFade(Color.YELLOW)
                .flicker(true)
                .build());
        fw.setFireworkMeta(meta);
        fw.detonate();
    }

    /** Kondigt iets aan op de hele server. */
    protected final void announce(Component message) {
        Bukkit.broadcast(message);
    }

    public final String getId() { return id; }
    public final String getDisplayName() { return displayName; }
    public final EventState getState() { return state; }
    public final int getPlayerCount() { return participants.size(); }
}
