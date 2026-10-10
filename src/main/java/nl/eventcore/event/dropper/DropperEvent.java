package nl.eventcore.event.dropper;

import com.destroystokyo.paper.ParticleBuilder;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import nl.eventcore.EventCorePlugin;
import nl.eventcore.event.EventState;
import nl.eventcore.event.GameEvent;
import nl.eventcore.event.RewardService;
import nl.eventcore.util.EventItems;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Dropper: val door een parcours zonder dood te gaan.
 *
 * Een dropper bestaat uit maps 1, 2, 3, ... Elke map heeft een start; door een portal van map N
 * ga je naar de start van map N+1. Wie een finish raakt (welke dan ook) is binnen.
 * De eerste X spelers (config: winners) krijgen een plek.
 *
 * Instellen: /event setup dropper start <map> | portal <map> (meerdere per map) | finish <nr> | lobby
 * en verder list, show en clear <start|portal|finish> <nr>.
 */
public final class DropperEvent extends GameEvent implements Listener {

    public static final String ID = "dropper";
    private static final String STARTS = ID + ".starts";
    private static final String PORTALS = ID + ".portals";
    private static final String FINISHES = ID + ".finishes";

    private enum Phase { PREP, PLAY }

    private record Result(UUID id, String name, long millis, int fails) {}

    // spelverloop
    private final Set<UUID> racers = new HashSet<>();
    private final Map<UUID, Integer> currentMap = new HashMap<>();
    private final Map<UUID, Integer> fails = new HashMap<>();     // totaal mis
    private final Map<UUID, Integer> mapFails = new HashMap<>();  // mis op de huidige map (voor de skip)
    private final Map<UUID, Vector> lastPos = new HashMap<>();
    private final Map<UUID, Long> resetCooldown = new HashMap<>();
    private final List<Result> results = new ArrayList<>();
    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask stepTask;
    private BukkitTask endTask;
    private BossBar bar;
    private Phase phase = Phase.PREP;
    private int lobbyLeft;
    private int counter;
    private long raceStart; // System.currentTimeMillis() bij het startsein
    private int startedWith;
    private boolean gameOver;

    // banen
    private Location lobby;
    private final TreeMap<Integer, Location> starts = new TreeMap<>();
    private final TreeMap<Integer, List<Location>> portals = new TreeMap<>();
    private final List<Location> finishes = new ArrayList<>();
    private int mapCount;

    // config
    private int minPlayers;
    private int maxPlayers;
    private int lobbyCountdown;
    private int startDelay;
    private int winDelaySeconds;
    private int places;
    private int maxDuration;
    private double portalRadius;
    private double portalHeight;
    private double finishRadius;
    private double finishHeight;
    private boolean resetOnFallDamage;
    private int skipAfterFails;
    private int voidDepth;

    public DropperEvent(EventCorePlugin plugin) {
        super(plugin, ID, "Dropper");
    }

    @Override
    public List<String> setupKeys() {
        return List.of("lobby", "start", "portal", "finish", "list", "show", "clear");
    }

    @Override
    public List<String> setupArgs(String key) {
        FileConfiguration cfg = plugin.getConfig();
        return switch (key) {
            case "start", "portal" -> numbers(Math.max(readStarts(cfg).size(), 1) + 1);
            case "finish" -> numbers(readFinishes(cfg).size() + 1);
            case "clear" -> List.of("start", "portal", "finish");
            default -> List.of();
        };
    }

    private static List<String> numbers(int max) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= max; i++) out.add(String.valueOf(i));
        return out;
    }

    private Component m(String key, Object... kv) {
        return plugin.messages().prefixed(key, kv);
    }

    private Component r(String key, Object... kv) {
        return plugin.messages().get(key, kv);
    }

    // ------------------------------------------------------------------
    // Opslag in config.yml
    // ------------------------------------------------------------------

    /** dropper.starts.<map> = locatie */
    private static TreeMap<Integer, Location> readStarts(FileConfiguration cfg) {
        TreeMap<Integer, Location> out = new TreeMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection(STARTS);
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) {
            Integer nr = parse(key);
            Location loc = sec.getLocation(key);
            if (nr != null && loc != null && loc.getWorld() != null) out.put(nr, loc);
        }
        return out;
    }

    /** dropper.portals.<map> = lijst locaties */
    private static TreeMap<Integer, List<Location>> readPortals(FileConfiguration cfg) {
        TreeMap<Integer, List<Location>> out = new TreeMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection(PORTALS);
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) {
            Integer nr = parse(key);
            if (nr == null) continue;
            List<Location> list = new ArrayList<>();
            List<?> raw = sec.getList(key);
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof Location loc && loc.getWorld() != null) list.add(loc);
                }
            }
            if (!list.isEmpty()) out.put(nr, list);
        }
        return out;
    }

    /** dropper.finishes.<nr> = locatie; het nummer is alleen om ze te kunnen vervangen/verwijderen. */
    private static TreeMap<Integer, Location> readFinishMap(FileConfiguration cfg) {
        TreeMap<Integer, Location> out = new TreeMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection(FINISHES);
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) {
            Integer nr = parse(key);
            Location loc = sec.getLocation(key);
            if (nr != null && loc != null && loc.getWorld() != null) out.put(nr, loc);
        }
        return out;
    }

    private static List<Location> readFinishes(FileConfiguration cfg) {
        return new ArrayList<>(readFinishMap(cfg).values());
    }

    private static Integer parse(String s) {
        try {
            int n = Integer.parseInt(s);
            return n >= 1 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Validatie & openen
    // ------------------------------------------------------------------

    @Override
    public Optional<Component> validate() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getLocation(ID + ".lobby") == null) return Optional.of(m("dropper.lobby-not-set"));

        TreeMap<Integer, Location> s = readStarts(cfg);
        if (s.isEmpty()) return Optional.of(m("dropper.no-starts"));
        // Maps moeten doorlopen: 1, 2, 3 zonder gaten.
        int last = s.lastKey();
        for (int i = 1; i <= last; i++) {
            if (!s.containsKey(i)) return Optional.of(m("dropper.start-missing", "map", i));
        }
        TreeMap<Integer, List<Location>> p = readPortals(cfg);
        for (int i = 1; i < last; i++) {
            if (!p.containsKey(i)) return Optional.of(m("dropper.portal-missing", "map", i, "next", i + 1));
        }
        if (readFinishes(cfg).isEmpty()) return Optional.of(m("dropper.no-finish"));
        return Optional.empty();
    }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        lobby = cfg.getLocation(ID + ".lobby");
        starts.clear();
        starts.putAll(readStarts(cfg));
        portals.clear();
        portals.putAll(readPortals(cfg));
        finishes.clear();
        finishes.addAll(readFinishes(cfg));
        mapCount = starts.lastKey();

        minPlayers = Math.max(1, cfg.getInt(ID + ".min-players", 2));
        maxPlayers = Math.max(minPlayers, cfg.getInt(ID + ".max-players", 20));
        lobbyCountdown = Math.max(5, cfg.getInt(ID + ".lobby-countdown", 30));
        startDelay = Math.max(3, cfg.getInt(ID + ".start-delay-seconds", 5));
        winDelaySeconds = Math.max(3, cfg.getInt(ID + ".win-delay-seconds", 8));
        places = Math.max(1, cfg.getInt(ID + ".winners", 3));
        maxDuration = Math.max(0, cfg.getInt(ID + ".max-duration-seconds", 600));
        portalRadius = Math.max(0.5, cfg.getDouble(ID + ".portal-radius", 2.0));
        portalHeight = Math.max(1.0, cfg.getDouble(ID + ".portal-height", 3.0));
        finishRadius = Math.max(0.5, cfg.getDouble(ID + ".finish-radius", 2.0));
        finishHeight = Math.max(1.0, cfg.getDouble(ID + ".finish-height", 3.0));
        resetOnFallDamage = cfg.getBoolean(ID + ".reset-on-fall-damage", true);
        skipAfterFails = Math.max(0, cfg.getInt(ID + ".skip-after-fails", 10));
        voidDepth = Math.max(5, cfg.getInt(ID + ".void-depth", 20));

        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        state = EventState.LOBBY;
        updateBar();

        announce(m("dropper.opened", "min", minPlayers, "maps", mapCount));
    }

    // ------------------------------------------------------------------
    // Join / leave
    // ------------------------------------------------------------------

    @Override
    protected void onJoin(Player player) {
        if (participants.size() > maxPlayers) {
            player.sendMessage(m("dropper.full"));
            leave(player);
            return;
        }

        player.teleport(lobby);
        player.showBossBar(bar);
        broadcastToParticipants(m("dropper.joined", "player", player.getName(),
                "count", participants.size(), "max", maxPlayers));

        if (state == EventState.LOBBY && participants.size() >= minPlayers) {
            startCountdown();
        }
        updateBar();
    }

    @Override
    protected void onLeave(Player player) {
        player.hideBossBar(bar);

        UUID id = player.getUniqueId();
        boolean wasRacing = racers.remove(id);
        currentMap.remove(id);
        fails.remove(id);
        mapFails.remove(id);
        lastPos.remove(id);
        resetCooldown.remove(id);

        if (state == EventState.COUNTDOWN && participants.size() < minPlayers) {
            cancelCountdown();
            state = EventState.LOBBY;
            broadcastToParticipants(m("dropper.countdown-cancelled"));
            updateBar();
            return;
        }

        if (state == EventState.RUNNING && !gameOver && wasRacing) {
            broadcastToParticipants(m("dropper.player-left", "player", player.getName()));
            checkEnd();
        }
        updateBar();
    }

    // ------------------------------------------------------------------
    // Bossbar
    // ------------------------------------------------------------------

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static String formatTime(long millis) {
        long tenths = millis / 100;
        return String.format("%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
    }

    /** Racetijd volgens de echte klok, zodat hij ook bij lag (minder dan 20 TPS) klopt. */
    private long raceMillis() {
        return phase == Phase.PLAY ? System.currentTimeMillis() - raceStart : 0L;
    }

    private void updateBar() {
        if (bar == null || gameOver) return;

        switch (state) {
            case LOBBY -> {
                bar.name(r("dropper.bossbar-lobby-waiting", "count", participants.size(), "min", minPlayers));
                bar.color(BossBar.Color.BLUE);
                bar.progress(clamp((float) participants.size() / minPlayers));
            }
            case COUNTDOWN -> {
                bar.name(r("dropper.bossbar-lobby-countdown", "seconds", lobbyLeft));
                bar.color(BossBar.Color.GREEN);
                bar.progress(clamp((float) lobbyLeft / lobbyCountdown));
            }
            case RUNNING -> {
                if (phase == Phase.PREP) {
                    bar.name(r("dropper.bossbar-prep", "seconds", counter));
                    bar.color(BossBar.Color.GREEN);
                    bar.progress(clamp((float) counter / startDelay));
                } else {
                    bar.name(r("dropper.bossbar-play", "finished", results.size(), "places", places,
                            "time", formatTime(raceMillis())));
                    bar.color(BossBar.Color.BLUE);
                    bar.progress(maxDuration > 0
                            ? clamp(1f - (float) raceMillis() / (maxDuration * 1000f))
                            : clamp((float) results.size() / Math.min(places, Math.max(1, startedWith))));
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------
    // Lobby-countdown & start
    // ------------------------------------------------------------------

    private void startCountdown() {
        state = EventState.COUNTDOWN;
        lobbyLeft = lobbyCountdown;

        countdownTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (lobbyLeft <= 0) {
                cancelCountdown();
                beginGame();
                return;
            }
            updateBar();
            if (lobbyLeft <= 5) {
                for (Player p : players()) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
                }
            }
            lobbyLeft--;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void beginGame() {
        state = EventState.RUNNING;
        gameOver = false;
        racers.clear();
        currentMap.clear();
        fails.clear();
        mapFails.clear();
        lastPos.clear();
        resetCooldown.clear();
        results.clear();

        Location first = starts.get(1);
        for (Player p : players()) {
            UUID id = p.getUniqueId();
            racers.add(id);
            currentMap.put(id, 1);
            fails.put(id, 0);
            mapFails.put(id, 0);

            p.teleport(first);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().setItem(8, resetItem());
            lastPos.put(id, first.toVector());

            p.showTitle(Title.title(
                    r("dropper.title-start"),
                    r("dropper.subtitle-start", "maps", mapCount),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        }
        startedWith = racers.size();

        phase = Phase.PREP;
        counter = startDelay;
        updateBar();
        gameTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        stepTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::step, 1L, 1L);
    }

    /** Rechtsklik = terug naar de start van je huidige map (als je vastzit). */
    private ItemStack resetItem() {
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(r("dropper.reset-item").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return EventItems.tag(item, "reset");
    }

    private boolean isResetItem(ItemStack item) {
        return EventItems.is(item, "reset");
    }

    /** Krijg je na skip-after-fails keer mis op dezelfde map. Rechtsklik = door naar de volgende map. */
    private ItemStack skipItem() {
        ItemStack item = new ItemStack(Material.IRON_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(r("dropper.skip-item").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return EventItems.tag(item, "skip");
    }

    private boolean isSkipItem(ItemStack item) {
        return EventItems.is(item, "skip");
    }

    // ------------------------------------------------------------------
    // Spelloop
    // ------------------------------------------------------------------

    /** 1x per seconde: startcountdown, tijdslimiet, bossbar en actionbar. */
    private void tick() {
        if (gameOver || state != EventState.RUNNING) return;

        if (phase == Phase.PREP) {
            updateBar();
            if (counter <= 0) {
                phase = Phase.PLAY;
                raceStart = System.currentTimeMillis();
                for (Player p : players()) {
                    p.showTitle(Title.title(
                            r("dropper.go-title"), r("dropper.go-subtitle"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(300))));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                }
            } else {
                if (counter <= 3) {
                    for (Player p : players()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.5f);
                }
                counter--;
            }
            return;
        }

        if (maxDuration > 0 && raceMillis() >= maxDuration * 1000L) {
            broadcastToParticipants(m("dropper.time-up"));
            endRace();
            return;
        }
        for (Player p : racerPlayers()) {
            UUID id = p.getUniqueId();
            p.sendActionBar(r("dropper.actionbar", "map", currentMap.getOrDefault(id, 1), "maps", mapCount,
                    "fails", fails.getOrDefault(id, 0), "time", formatTime(raceMillis())));
        }
        updateBar();
    }

    /** Elke tick: portal of finish geraakt? Uit de map gevallen? */
    private void step() {
        if (gameOver || state != EventState.RUNNING || phase != Phase.PLAY) return;

        for (Player p : racerPlayers()) {
            if (gameOver) return;
            if (checkGoal(p)) continue;

            int map = currentMap.getOrDefault(p.getUniqueId(), 1);
            Location loc = p.getLocation();
            Location start = starts.get(map);
            if (loc.getWorld().equals(start.getWorld()) && loc.getY() < lowestPoint(map) - voidDepth) {
                fail(p, false);
            }
        }
    }

    /**
     * Heeft de speler sinds de vorige keer een finish of een portal van zijn map geraakt?
     * Zo ja: binnen of door naar de volgende map, en true.
     */
    private boolean checkGoal(Player p) {
        UUID id = p.getUniqueId();
        Vector now = p.getLocation().toVector();
        Vector before = lastPos.getOrDefault(id, now);
        lastPos.put(id, now);

        if (touchesAny(p, before, now, finishes, finishRadius, finishHeight)) {
            finishRacer(p);
            return true;
        }
        int map = currentMap.getOrDefault(id, 1);
        List<Location> exits = portals.get(map);
        if (exits != null && touchesAny(p, before, now, exits, portalRadius, portalHeight)) {
            if (map >= mapCount) finishRacer(p); // portal in de laatste map telt als finish
            else nextMap(p, map + 1);
            return true;
        }
        return false;
    }

    /** Laagste punt van een map (start, portals, finishes in dezelfde wereld), voor de leegte-check. */
    private double lowestPoint(int map) {
        Location start = starts.get(map);
        double lowest = start.getY();
        for (Location l : portals.getOrDefault(map, List.of())) {
            if (l.getWorld().equals(start.getWorld())) lowest = Math.min(lowest, l.getY());
        }
        for (Location l : finishes) {
            if (l.getWorld().equals(start.getWorld())) lowest = Math.min(lowest, l.getY());
        }
        return lowest;
    }

    /**
     * Raakt het stuk tussen de vorige en de huidige positie het gebied rond een van de punten?
     * Gebied = zo groot als een netherportal: radius blokken opzij (alle kanten op) en height blokken hoog.
     * Door het stuk te controleren val je er nooit "doorheen" als je snel valt.
     */
    private static boolean touchesAny(Player p, Vector from, Vector to, List<Location> points, double radius, double height) {
        for (Location c : points) {
            if (!p.getWorld().equals(c.getWorld())) continue;
            BoundingBox box = area(c, radius, height);
            if (box.contains(to) || box.contains(from)) return true;
            Vector dir = to.clone().subtract(from);
            double len = dir.length();
            if (len > 1e-6 && box.rayTrace(from, dir.normalize(), len) != null) return true;
        }
        return false;
    }

    private static BoundingBox area(Location c, double radius, double height) {
        return new BoundingBox(c.getX() - radius, c.getY() - 0.5, c.getZ() - radius,
                c.getX() + radius, c.getY() + height + 0.5, c.getZ() + radius);
    }

    private void nextMap(Player p, int map) {
        UUID id = p.getUniqueId();
        currentMap.put(id, map);
        mapFails.put(id, 0);
        Location start = starts.get(map);
        teleportSafe(p, start);
        p.playSound(start, Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 1.6f);
        p.showTitle(Title.title(
                r("dropper.map-title", "map", map, "maps", mapCount),
                r("dropper.map-subtitle", "time", formatTime(raceMillis())),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(300))));
    }

    /** Mislukt: terug naar de start van je huidige map. */
    private void fail(Player p, boolean manual) {
        UUID id = p.getUniqueId();
        int map = currentMap.getOrDefault(id, 1);
        if (!manual) fails.merge(id, 1, Integer::sum);
        int onThisMap = manual ? mapFails.getOrDefault(id, 0) : mapFails.merge(id, 1, Integer::sum);
        teleportSafe(p, starts.get(map));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
        p.sendActionBar(manual ? r("dropper.reset-manual", "map", map) : r("dropper.reset-fail", "map", map,
                "fails", fails.getOrDefault(id, 0)));

        // Precies bij de X-ste keer mis op deze map: een skip (één per map, nooit voor de laatste map).
        if (!manual && skipAfterFails > 0 && onThisMap == skipAfterFails && map < mapCount) {
            p.getInventory().addItem(skipItem());
            p.sendMessage(m("dropper.skip-earned", "fails", skipAfterFails, "map", map));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
        }
    }

    /** Skip gebruiken: één ijzerstaaf minder en door naar de volgende map. */
    private void useSkip(Player p, ItemStack item) {
        int map = currentMap.getOrDefault(p.getUniqueId(), 1);
        if (map >= mapCount) {
            p.sendMessage(m("dropper.skip-last-map"));
            return;
        }
        item.setAmount(item.getAmount() - 1);
        broadcastToParticipants(m("dropper.skip-used", "player", p.getName(), "map", map));
        nextMap(p, map + 1);
    }

    private void teleportSafe(Player p, Location target) {
        p.setVelocity(new Vector());
        p.setFallDistance(0);
        p.setFireTicks(0);
        p.teleport(target);
        lastPos.put(p.getUniqueId(), target.toVector());
    }

    private void finishRacer(Player p) {
        UUID id = p.getUniqueId();
        long time = raceMillis();
        int f = fails.getOrDefault(id, 0);
        racers.remove(id);
        results.add(new Result(id, p.getName(), time, f));
        int place = results.size();

        broadcastToParticipants(m("dropper.finished", "player", p.getName(), "place", place,
                "time", formatTime(time), "fails", f));
        p.showTitle(Title.title(
                r("dropper.finished-title", "place", place),
                r("dropper.finished-subtitle", "time", formatTime(time), "fails", f),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        if (place == 1) celebrate(p.getLocation());

        // Klaar: toekijken vanaf de plek waar je binnenkwam.
        p.setFallDistance(0);
        p.setGameMode(GameMode.SPECTATOR);

        checkEnd();
    }

    private List<Player> racerPlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : racers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) list.add(p);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Einde & uitslag
    // ------------------------------------------------------------------

    private void checkEnd() {
        if (gameOver || state != EventState.RUNNING) return;
        if (results.size() >= places || racers.isEmpty()) endRace();
    }

    private void endRace() {
        if (gameOver) return;
        gameOver = true;
        if (gameTask != null) { gameTask.cancel(); gameTask = null; }
        if (stepTask != null) { stepTask.cancel(); stepTask = null; }

        List<Result> podium = results.subList(0, Math.min(places, results.size()));
        if (podium.isEmpty()) {
            announce(m("dropper.no-finishers"));
            bar.name(r("dropper.bossbar-nowinner"));
        } else {
            announce(m("dropper.results-header"));
            for (int i = 0; i < podium.size(); i++) {
                Result res = podium.get(i);
                announce(r("dropper.results-line", "place", i + 1, "player", res.name(),
                        "time", formatTime(res.millis()), "fails", res.fails()));
            }
            String winner = podium.getFirst().name();
            for (Player p : players()) {
                p.showTitle(Title.title(
                        r("dropper.winner-title", "player", winner),
                        r("dropper.winner-subtitle", "player", winner),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            }
            bar.name(r("dropper.bossbar-winner", "player", winner));
        }
        bar.color(BossBar.Color.PURPLE);
        bar.progress(1f);

        List<Player> ranking = new ArrayList<>();
        for (Result res : podium) {
            Player p = Bukkit.getPlayer(res.id());
            if (p != null && isParticipant(p)) ranking.add(p);
        }
        RewardService.givePlaces(plugin, ID, startedWith, ranking, players());

        endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> stop(false), winDelaySeconds * 20L);
    }

    // ------------------------------------------------------------------
    // Afsluiten
    // ------------------------------------------------------------------

    @Override
    protected void onStop(boolean forced) {
        cancelCountdown();
        if (gameTask != null) { gameTask.cancel(); gameTask = null; }
        if (stepTask != null) { stepTask.cancel(); stepTask = null; }
        if (endTask != null) { endTask.cancel(); endTask = null; }

        if (bar != null) {
            for (Player p : players()) p.hideBossBar(bar);
        }
        racers.clear();
        currentMap.clear();
        fails.clear();
        mapFails.clear();
        lastPos.clear();
        resetCooldown.clear();
        gameOver = true;
        HandlerList.unregisterAll(this);

        if (forced) announce(m("dropper.stopped"));
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    @Override
    public boolean handleSetup(Player p, String key, String[] args) {
        FileConfiguration cfg = plugin.getConfig();
        switch (key) {
            case "start" -> {
                Integer nr = number(p, args, 0, "dropper.setup-start-usage");
                if (nr == null) return true;
                cfg.set(STARTS + "." + nr, p.getLocation());
                plugin.saveConfig();
                p.sendMessage(m("dropper.start-set", "map", nr));
            }
            case "finish" -> {
                Integer nr = number(p, args, 0, "dropper.setup-finish-usage");
                if (nr == null) return true;
                cfg.set(FINISHES + "." + nr, p.getLocation());
                plugin.saveConfig();
                p.sendMessage(m("dropper.finish-set", "nr", nr));
            }
            case "portal" -> {
                Integer nr = number(p, args, 0, "dropper.setup-portal-usage");
                if (nr == null) return true;
                List<Location> list = new ArrayList<>(readPortals(cfg).getOrDefault(nr, List.of()));
                list.add(p.getLocation());
                cfg.set(PORTALS + "." + nr, list);
                plugin.saveConfig();
                p.sendMessage(m("dropper.portal-added", "map", nr, "count", list.size(), "next", nr + 1));
            }
            case "list" -> list(p, cfg);
            case "show" -> show(p, cfg);
            case "clear" -> {
                String what = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
                if (!List.of("start", "portal", "finish").contains(what)) {
                    p.sendMessage(m("dropper.setup-clear-usage"));
                    return true;
                }
                Integer nr = number(p, args, 1, "dropper.setup-clear-usage");
                if (nr == null) return true;
                String path = switch (what) {
                    case "start" -> STARTS;
                    case "portal" -> PORTALS;
                    default -> FINISHES;
                } + "." + nr;
                if (!cfg.contains(path)) {
                    p.sendMessage(m("dropper.clear-nothing", "what", what, "nr", nr));
                    return true;
                }
                cfg.set(path, null);
                plugin.saveConfig();
                p.sendMessage(m("dropper.cleared", "what", what, "nr", nr));
            }
            default -> {
                return false; // lobby: gewoon je huidige positie opslaan
            }
        }
        return true;
    }

    /** Leest een nummer (1 of hoger) uit args[index]; stuurt anders de gebruiksregel. */
    private Integer number(Player p, String[] args, int index, String usageKey) {
        Integer nr = args.length > index ? parse(args[index]) : null;
        if (nr == null) p.sendMessage(m(usageKey));
        return nr;
    }

    private void list(Player p, FileConfiguration cfg) {
        TreeMap<Integer, Location> s = readStarts(cfg);
        TreeMap<Integer, List<Location>> pt = readPortals(cfg);
        TreeMap<Integer, Location> f = readFinishMap(cfg);
        p.sendMessage(m("dropper.list-header", "maps", s.isEmpty() ? 0 : s.lastKey(), "finishes", f.size()));

        int last = Math.max(s.isEmpty() ? 0 : s.lastKey(), pt.isEmpty() ? 0 : pt.lastKey());
        for (int i = 1; i <= last; i++) {
            Location st = s.get(i);
            p.sendMessage(r("dropper.list-map", "map", i,
                    "start", st == null ? "-" : coords(st),
                    "portals", pt.getOrDefault(i, List.of()).size()));
        }
        for (Map.Entry<Integer, Location> e : f.entrySet()) {
            p.sendMessage(r("dropper.list-finish", "nr", e.getKey(), "pos", coords(e.getValue())));
        }
    }

    private static String coords(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    /** Laat 10 seconden alle portals (paars), finishes (geel) en starts (groen) zien. */
    private void show(Player p, FileConfiguration cfg) {
        double pr = Math.max(0.5, cfg.getDouble(ID + ".portal-radius", 2.0));
        double ph = Math.max(1.0, cfg.getDouble(ID + ".portal-height", 3.0));
        double fr = Math.max(0.5, cfg.getDouble(ID + ".finish-radius", 2.0));
        double fh = Math.max(1.0, cfg.getDouble(ID + ".finish-height", 3.0));
        List<Location> portalList = new ArrayList<>();
        readPortals(cfg).values().forEach(portalList::addAll);
        List<Location> finishList = readFinishes(cfg);
        List<Location> startList = new ArrayList<>(readStarts(cfg).values());
        p.sendMessage(m("dropper.show"));

        int[] runs = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || runs[0]++ >= 20) {
                task[0].cancel();
                return;
            }
            for (Location l : portalList) drawBox(p, area(l, pr, ph), Color.PURPLE);
            for (Location l : finishList) drawBox(p, area(l, fr, fh), Color.YELLOW);
            for (Location l : startList) drawBox(p, area(l, 0.5, 1.5), Color.LIME);
        }, 0L, 10L);
    }

    /** Tekent de ribben van een gebied met deeltjes. */
    private static void drawBox(Player viewer, BoundingBox box, Color color) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);
        double[] xs = {box.getMinX(), box.getMaxX()};
        double[] ys = {box.getMinY(), box.getMaxY()};
        double[] zs = {box.getMinZ(), box.getMaxZ()};
        double step = 0.5;
        for (double y : ys) for (double z : zs) line(viewer, dust, box.getMinX(), y, z, box.getMaxX(), y, z, step);
        for (double x : xs) for (double z : zs) line(viewer, dust, x, box.getMinY(), z, x, box.getMaxY(), z, step);
        for (double x : xs) for (double y : ys) line(viewer, dust, x, y, box.getMinZ(), x, y, box.getMaxZ(), step);
    }

    private static void line(Player viewer, Particle.DustOptions dust,
                             double x1, double y1, double z1, double x2, double y2, double z2, double step) {
        double len = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int n = Math.max(1, (int) Math.ceil(len / step));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            Location at = new Location(viewer.getWorld(), x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t);
            new ParticleBuilder(Particle.DUST).location(at).data(dust).receivers(viewer).force(true).spawn();
        }
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    private boolean inEvent(Entity entity) {
        return entity instanceof Player p && isParticipant(p);
    }

    private boolean racing(Player p) {
        return state == EventState.RUNNING && !gameOver && racers.contains(p.getUniqueId());
    }

    /** Tijdens de startcountdown sta je stil (rondkijken mag). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!racing(e.getPlayer()) || phase != Phase.PREP) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    /** Hard neerkomen = mislukt, terug naar de start van de map. Geen andere schade. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isParticipant(p)) return;

        boolean fall = e.getCause() == EntityDamageEvent.DamageCause.FALL;
        boolean voidDmg = e.getCause() == EntityDamageEvent.DamageCause.VOID;
        if (racing(p) && phase == Phase.PLAY && (fall || voidDmg)) {
            // Landen op een finish of portal die op de grond ligt: dat telt, niet als mislukt.
            if (checkGoal(p)) {
                e.setCancelled(true);
                return;
            }
            boolean lethal = e.getFinalDamage() >= p.getHealth();
            if (resetOnFallDamage || lethal || voidDmg) {
                e.setCancelled(true);
                fail(p, false);
                return;
            }
            return; // alleen een beetje schade
        }
        e.setCancelled(true);
    }

    /** Rechtsklik met de rode kleurstof: opnieuw beginnen aan je map. Met de ijzerstaaf: map overslaan. */
    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL) return;
        ItemStack item = e.getItem();
        boolean reset = isResetItem(item);
        boolean skip = isSkipItem(item);
        if (!reset && !skip) return;
        Player p = e.getPlayer();
        if (!isParticipant(p)) return;
        e.setCancelled(true);
        if (!racing(p) || phase != Phase.PLAY) return;

        long now = System.currentTimeMillis();
        if (resetCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        resetCooldown.put(p.getUniqueId(), now + 1000L);
        if (skip) useSkip(p, item);
        else fail(p, true);
    }

    /** Een echte netherportal mag je niet naar de Nether sturen. */
    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (isParticipant(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalEnter(EntityPortalEnterEvent e) {
        if (inEvent(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (inEvent(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isParticipant(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isParticipant(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (inEvent(e.getWhoClicked())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isParticipant(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isParticipant(e.getPlayer())) e.setCancelled(true);
    }
}
