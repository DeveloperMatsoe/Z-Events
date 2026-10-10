package nl.eventcore.event.elytrarace;

import com.destroystokyo.paper.ParticleBuilder;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import nl.eventcore.EventCorePlugin;
import nl.eventcore.event.EventState;
import nl.eventcore.event.GameEvent;
import nl.eventcore.event.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
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
import java.util.UUID;

/**
 * Elytra Race: vlieg met een elytra op volgorde door alle checkpoints naar de finish.
 * De eerste X spelers (config: winners, bv. 1 of 3) krijgen een plek; daarna is de race voorbij.
 *
 * Instellen: /event setup elytrarace lobby | start | finish  (je kijkrichting telt mee)
 * en /event setup elytrarace checkpoint [add|insert|remove|tp|list|show|clear].
 */
public final class ElytraRaceEvent extends GameEvent implements Listener {

    public static final String ID = "elytrarace";
    private static final String CHECKPOINTS = ID + ".checkpoints";
    private static final List<String> CHECKPOINT_ACTIONS = List.of("add", "insert", "remove", "tp", "list", "show", "clear");

    private enum Phase { PREP, PLAY }

    private record Result(UUID id, String name, long millis) {}

    // spelverloop
    private final Set<UUID> racers = new HashSet<>();           // nog onderweg
    private final Map<UUID, Integer> progress = new HashMap<>(); // aantal gehaalde checkpoints
    private final Map<UUID, Vector> lastPos = new HashMap<>();
    private final Set<UUID> airborne = new HashSet<>();          // heeft de start verlaten (grond raken = terugzetten)
    private final List<Result> results = new ArrayList<>();
    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask stepTask;
    private BukkitTask endTask;
    private BossBar bar;
    private Phase phase = Phase.PREP;
    private int lobbyLeft;
    private int counter;
    private int steps;
    private long raceStart; // System.currentTimeMillis() bij het startsein
    private int startedWith;
    private boolean gameOver;

    // arena
    private Location lobby;
    private Location start;
    private Location finish;
    private final List<Location> checkpoints = new ArrayList<>();
    private double resetY;

    // config
    private int minPlayers;
    private int maxPlayers;
    private int lobbyCountdown;
    private int startDelay;
    private int winDelaySeconds;
    private int places;
    private int maxDuration;
    private int rockets;
    private int rocketsPerCheckpoint;
    private double checkpointRadius;
    private double finishRadius;
    private double startSpread;
    private boolean resetOnGround;

    public ElytraRaceEvent(EventCorePlugin plugin) {
        super(plugin, ID, "Elytra Race");
    }

    @Override
    public List<String> setupKeys() {
        return List.of("lobby", "start", "finish", "checkpoint");
    }

    @Override
    public List<String> setupArgs(String key) {
        return key.equals("checkpoint") ? CHECKPOINT_ACTIONS : List.of();
    }

    private Component m(String key, Object... kv) {
        return plugin.messages().prefixed(key, kv);
    }

    private Component r(String key, Object... kv) {
        return plugin.messages().get(key, kv);
    }

    // ------------------------------------------------------------------
    // Validatie & openen
    // ------------------------------------------------------------------

    @Override
    public Optional<Component> validate() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getLocation(ID + ".lobby") == null) return Optional.of(m("elytrarace.lobby-not-set"));
        Location s = cfg.getLocation(ID + ".start");
        Location f = cfg.getLocation(ID + ".finish");
        if (s == null) return Optional.of(m("elytrarace.start-not-set"));
        if (f == null) return Optional.of(m("elytrarace.finish-not-set"));

        World world = s.getWorld();
        if (world == null || !world.equals(f.getWorld())) return Optional.of(m("elytrarace.world-mismatch"));
        for (Location cp : readCheckpoints(cfg)) {
            if (!world.equals(cp.getWorld())) return Optional.of(m("elytrarace.world-mismatch"));
        }
        return Optional.empty();
    }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        lobby = cfg.getLocation(ID + ".lobby");
        start = cfg.getLocation(ID + ".start");
        finish = cfg.getLocation(ID + ".finish");
        checkpoints.clear();
        checkpoints.addAll(readCheckpoints(cfg));

        minPlayers = Math.max(1, cfg.getInt(ID + ".min-players", 2));
        maxPlayers = Math.max(minPlayers, cfg.getInt(ID + ".max-players", 20));
        lobbyCountdown = Math.max(5, cfg.getInt(ID + ".lobby-countdown", 30));
        startDelay = Math.max(3, cfg.getInt(ID + ".start-delay-seconds", 5));
        winDelaySeconds = Math.max(3, cfg.getInt(ID + ".win-delay-seconds", 8));
        places = Math.max(1, cfg.getInt(ID + ".winners", 3));
        maxDuration = Math.max(0, cfg.getInt(ID + ".max-duration-seconds", 300));
        rockets = Math.max(0, cfg.getInt(ID + ".rockets", 3));
        rocketsPerCheckpoint = Math.max(0, cfg.getInt(ID + ".rockets-per-checkpoint", 1));
        checkpointRadius = Math.max(1.0, cfg.getDouble(ID + ".checkpoint-radius", 6.0));
        finishRadius = Math.max(1.0, cfg.getDouble(ID + ".finish-radius", 8.0));
        startSpread = Math.max(0.0, cfg.getDouble(ID + ".start-spread", 1.5));
        resetOnGround = cfg.getBoolean(ID + ".reset-on-ground", true);

        // Wie zo ver onder het laagste punt van de baan zakt, wordt teruggezet.
        double lowest = Math.min(start.getY(), finish.getY());
        for (Location cp : checkpoints) lowest = Math.min(lowest, cp.getY());
        resetY = lowest - Math.max(5, cfg.getInt(ID + ".void-depth", 40));

        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        state = EventState.LOBBY;
        updateBar();

        announce(m("elytrarace.opened", "min", minPlayers, "checkpoints", checkpoints.size()));
    }

    // ------------------------------------------------------------------
    // Join / leave
    // ------------------------------------------------------------------

    @Override
    protected void onJoin(Player player) {
        if (participants.size() > maxPlayers) {
            player.sendMessage(m("elytrarace.full"));
            leave(player);
            return;
        }

        player.teleport(lobby);
        player.showBossBar(bar);
        broadcastToParticipants(m("elytrarace.joined", "player", player.getName(),
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
        progress.remove(id);
        lastPos.remove(id);
        airborne.remove(id);

        if (state == EventState.COUNTDOWN && participants.size() < minPlayers) {
            cancelCountdown();
            state = EventState.LOBBY;
            broadcastToParticipants(m("elytrarace.countdown-cancelled"));
            updateBar();
            return;
        }

        if (state == EventState.RUNNING && !gameOver && wasRacing) {
            broadcastToParticipants(m("elytrarace.player-left", "player", player.getName()));
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
                bar.name(r("elytrarace.bossbar-lobby-waiting", "count", participants.size(), "min", minPlayers));
                bar.color(BossBar.Color.PINK);
                bar.progress(clamp((float) participants.size() / minPlayers));
            }
            case COUNTDOWN -> {
                bar.name(r("elytrarace.bossbar-lobby-countdown", "seconds", lobbyLeft));
                bar.color(BossBar.Color.GREEN);
                bar.progress(clamp((float) lobbyLeft / lobbyCountdown));
            }
            case RUNNING -> {
                if (phase == Phase.PREP) {
                    bar.name(r("elytrarace.bossbar-prep", "seconds", counter));
                    bar.color(BossBar.Color.GREEN);
                    bar.progress(clamp((float) counter / startDelay));
                } else {
                    bar.name(r("elytrarace.bossbar-play", "finished", results.size(), "places", places,
                            "time", formatTime(raceMillis())));
                    bar.color(BossBar.Color.PURPLE);
                    // Met tijdslimiet loopt de balk leeg, anders vult hij zich met het aantal finishers.
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
        steps = 0;
        racers.clear();
        progress.clear();
        lastPos.clear();
        airborne.clear();
        results.clear();

        int i = 0;
        for (Player p : players()) {
            UUID id = p.getUniqueId();
            racers.add(id);
            progress.put(id, 0);

            Location spot = startSpot(i++);
            p.teleport(spot);
            p.setGameMode(GameMode.ADVENTURE);
            equip(p);
            lastPos.put(id, spot.toVector());

            p.showTitle(Title.title(
                    r("elytrarace.title-start"),
                    r("elytrarace.subtitle-start", "checkpoints", checkpoints.size()),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_ELYTRA, 1f, 1f);
        }
        startedWith = racers.size();

        // Korte voorbereiding op de startlijn: iedereen staat stil.
        phase = Phase.PREP;
        counter = startDelay;
        updateBar();
        gameTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        stepTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::step, 2L, 2L);
    }

    /** Spelers naast elkaar op de startlijn (om en om links/rechts), als daar plek is. */
    private Location startSpot(int index) {
        if (index == 0 || startSpread <= 0) return start.clone();

        int row = (index + 1) / 2;
        double side = (index % 2 == 1 ? 1 : -1) * row * startSpread;
        double yaw = Math.toRadians(start.getYaw());
        Location spot = start.clone().add(Math.cos(yaw) * side, 0, Math.sin(yaw) * side);

        Block feet = spot.getBlock();
        boolean free = feet.isPassable() && feet.getRelative(0, 1, 0).isPassable();
        boolean floor = feet.getRelative(0, -1, 0).isSolid();
        return free && floor ? spot : start.clone();
    }

    private void equip(Player p) {
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        meta.displayName(r("elytrarace.elytra-name"));
        meta.setUnbreakable(true);
        elytra.setItemMeta(meta);
        p.getInventory().setChestplate(elytra);

        if (rockets > 0) p.getInventory().setItem(0, new ItemStack(Material.FIREWORK_ROCKET, rockets));
    }

    // ------------------------------------------------------------------
    // Spelloop
    // ------------------------------------------------------------------

    /** 1x per seconde: startcountdown, tijdslimiet en bossbar. */
    private void tick() {
        if (gameOver || state != EventState.RUNNING) return;

        if (phase == Phase.PREP) {
            updateBar();
            if (counter <= 0) {
                phase = Phase.PLAY;
                raceStart = System.currentTimeMillis();
                for (Player p : players()) {
                    p.showTitle(Title.title(
                            r("elytrarace.go-title"), r("elytrarace.go-subtitle"),
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
            broadcastToParticipants(m("elytrarace.time-up"));
            endRace();
            return;
        }
        updateBar();
    }

    /** Elke 2 ticks: checkpoints, finish en terugzetten. */
    private void step() {
        if (gameOver || state != EventState.RUNNING || phase != Phase.PLAY) return;
        steps++;
        boolean visuals = steps % 5 == 0; // elke halve seconde ringen en actionbar

        for (Player p : racerPlayers()) {
            if (gameOver) return; // de laatste plek is net vergeven
            UUID id = p.getUniqueId();
            Location loc = p.getLocation();
            Vector now = loc.toVector();
            Vector before = lastPos.getOrDefault(id, now);
            lastPos.put(id, now);

            if (p.isGliding() || p.getFallDistance() > 4f) airborne.add(id);

            boolean touched = airborne.contains(id) && (standsOnBlock(loc) || p.isInWater() || p.isInLava());
            if (loc.getY() < resetY || (resetOnGround && touched)) {
                reset(p);
                continue;
            }

            int done = progress.getOrDefault(id, 0);
            if (done < checkpoints.size()) {
                if (passes(before, now, checkpoints.get(done), checkpointRadius)) reachCheckpoint(p, done + 1);
            } else if (passes(before, now, finish, finishRadius)) {
                finishRacer(p);
                continue;
            }

            if (visuals) showProgress(p);
        }
    }

    private static final double[][] FOOT_CORNERS = {{0, 0}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}};

    /**
     * Staat de speler op een vast blok? De server kijkt zelf onder de voeten (midden en de hoeken van de hitbox).
     * Player#isOnGround is deprecated: die waarde komt van de client en is met een hack te vervalsen.
     */
    private static boolean standsOnBlock(Location loc) {
        double y = loc.getY() - 0.05;
        for (double[] c : FOOT_CORNERS) {
            Block below = loc.getWorld().getBlockAt(
                    (int) Math.floor(loc.getX() + c[0]), (int) Math.floor(y), (int) Math.floor(loc.getZ() + c[1]));
            if (below.isSolid() && below.getBoundingBox().getMaxY() >= y) return true;
        }
        return false;
    }

    /**
     * Raakt het stuk tussen de vorige en de huidige positie de bol rond het doel?
     * Zo mis je geen checkpoint als je er in één stap doorheen vliegt.
     */
    private static boolean passes(Vector from, Vector to, Location target, double radius) {
        Vector c = target.toVector();
        Vector seg = to.clone().subtract(from);
        double len2 = seg.lengthSquared();
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, c.clone().subtract(from).dot(seg) / len2));
        Vector closest = from.clone().add(seg.multiply(t));
        return closest.distanceSquared(c) <= radius * radius;
    }

    private void reachCheckpoint(Player p, int number) {
        progress.put(p.getUniqueId(), number);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
        p.sendMessage(m("elytrarace.checkpoint-reached", "nr", number, "total", checkpoints.size(),
                "time", formatTime(raceMillis())));
        if (rocketsPerCheckpoint > 0) p.getInventory().addItem(new ItemStack(Material.FIREWORK_ROCKET, rocketsPerCheckpoint));
    }

    /** Terug naar het laatst gehaalde checkpoint (of de start). */
    private void reset(Player p) {
        UUID id = p.getUniqueId();
        int done = progress.getOrDefault(id, 0);
        Location target = done == 0 ? start.clone() : checkpoints.get(done - 1).clone();

        p.setVelocity(new Vector());
        p.setFallDistance(0);
        p.teleport(target);
        lastPos.put(id, target.toVector());
        p.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
        p.sendMessage(done == 0 ? m("elytrarace.reset-start") : m("elytrarace.reset-checkpoint", "nr", done));

        // Staat het punt op de grond (zoals de start), dan mag je gewoon opnieuw springen.
        if (target.clone().subtract(0, 0.2, 0).getBlock().isSolid()) {
            airborne.remove(id);
            return;
        }
        // Midden in de lucht: meteen weer laten glijden in de vliegrichting van het checkpoint.
        airborne.add(id);
        Vector push = target.getDirection().multiply(0.6);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!racers.contains(id) || gameOver) return;
            p.setGliding(true);
            p.setVelocity(push);
        }, 1L);
    }

    private void finishRacer(Player p) {
        UUID id = p.getUniqueId();
        long time = raceMillis();
        racers.remove(id);
        airborne.remove(id);
        results.add(new Result(id, p.getName(), time));
        int place = results.size();

        broadcastToParticipants(m("elytrarace.finished", "player", p.getName(), "place", place,
                "time", formatTime(time)));
        p.showTitle(Title.title(
                r("elytrarace.finished-title", "place", place),
                r("elytrarace.finished-subtitle", "time", formatTime(time)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        if (place == 1) celebrate(p.getLocation());

        // Klaar: toekijken vanaf de finish.
        p.setGliding(false);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(finish);

        checkEnd();
    }

    /** Ring van het volgende doel (alleen voor deze speler zichtbaar) en de voortgang in de actionbar. */
    private void showProgress(Player p) {
        int done = progress.getOrDefault(p.getUniqueId(), 0);
        String time = formatTime(raceMillis());
        if (done < checkpoints.size()) {
            drawRing(p, checkpoints.get(done), checkpointRadius, Color.LIME);
            p.sendActionBar(r("elytrarace.actionbar-checkpoint", "nr", done + 1, "total", checkpoints.size(), "time", time));
        } else {
            drawRing(p, finish, finishRadius, Color.YELLOW);
            p.sendActionBar(r("elytrarace.actionbar-finish", "time", time));
        }
    }

    /** Tekent een ring loodrecht op de kijkrichting van het punt (de richting waarin je erdoor vliegt). */
    private static void drawRing(Player viewer, Location center, double radius, Color color) {
        if (!viewer.getWorld().equals(center.getWorld())) return;

        Vector forward = center.getDirection();
        Vector u = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (u.lengthSquared() < 1e-4) u = new Vector(1, 0, 0);
        u.normalize();
        Vector v = u.clone().crossProduct(forward).normalize();

        Particle.DustOptions dust = new Particle.DustOptions(color, 1.8f);
        int points = Math.max(16, (int) (radius * 6));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            Location at = center.clone()
                    .add(u.clone().multiply(Math.cos(a) * radius))
                    .add(v.clone().multiply(Math.sin(a) * radius));
            // force: ook van ver te zien, zodat je weet waar je heen moet.
            new ParticleBuilder(Particle.DUST).location(at).data(dust).receivers(viewer).force(true).spawn();
        }
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

    /** Klaar als alle plekken vergeven zijn of er niemand meer onderweg is. */
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
            announce(m("elytrarace.no-finishers"));
            bar.name(r("elytrarace.bossbar-nowinner"));
        } else {
            announce(m("elytrarace.results-header"));
            for (int i = 0; i < podium.size(); i++) {
                Result res = podium.get(i);
                announce(r("elytrarace.results-line", "place", i + 1, "player", res.name(),
                        "time", formatTime(res.millis())));
            }
            String winner = podium.getFirst().name();
            for (Player p : players()) {
                p.showTitle(Title.title(
                        r("elytrarace.winner-title", "player", winner),
                        r("elytrarace.winner-subtitle", "player", winner),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            }
            bar.name(r("elytrarace.bossbar-winner", "player", winner));
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
        for (Player p : players()) p.setGliding(false);
        racers.clear();
        progress.clear();
        lastPos.clear();
        airborne.clear();
        gameOver = true;
        HandlerList.unregisterAll(this);

        if (forced) announce(m("elytrarace.stopped"));
    }

    // ------------------------------------------------------------------
    // Setup: checkpoints
    // ------------------------------------------------------------------

    private static List<Location> readCheckpoints(FileConfiguration cfg) {
        List<Location> list = new ArrayList<>();
        List<?> raw = cfg.getList(CHECKPOINTS);
        if (raw == null) return list;
        for (Object o : raw) {
            if (o instanceof Location loc && loc.getWorld() != null) list.add(loc);
        }
        return list;
    }

    private void saveCheckpoints(List<Location> list) {
        plugin.getConfig().set(CHECKPOINTS, list);
        plugin.saveConfig();
    }

    @Override
    public boolean handleSetup(Player p, String key, String[] args) {
        if (!key.equals("checkpoint")) return false;

        List<Location> list = readCheckpoints(plugin.getConfig());
        String action = args.length == 0 ? "add" : args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "add" -> {
                list.add(p.getLocation());
                saveCheckpoints(list);
                p.sendMessage(m("elytrarace.checkpoint-added", "nr", list.size()));
            }
            case "insert" -> {
                Integer nr = number(p, args, list.size() + 1);
                if (nr == null) return true;
                list.add(nr - 1, p.getLocation());
                saveCheckpoints(list);
                p.sendMessage(m("elytrarace.checkpoint-inserted", "nr", nr));
            }
            case "remove" -> {
                Integer nr = number(p, args, list.size());
                if (nr == null) return true;
                list.remove(nr - 1);
                saveCheckpoints(list);
                p.sendMessage(m("elytrarace.checkpoint-removed", "nr", nr, "count", list.size()));
            }
            case "tp" -> {
                Integer nr = number(p, args, list.size());
                if (nr == null) return true;
                p.teleport(list.get(nr - 1));
                p.sendMessage(m("elytrarace.checkpoint-tp", "nr", nr));
            }
            case "list" -> {
                if (list.isEmpty()) {
                    p.sendMessage(m("elytrarace.checkpoint-none"));
                    return true;
                }
                p.sendMessage(m("elytrarace.checkpoint-list-header", "count", list.size()));
                for (int i = 0; i < list.size(); i++) {
                    Location l = list.get(i);
                    p.sendMessage(r("elytrarace.checkpoint-list-line", "nr", i + 1,
                            "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ()));
                }
            }
            case "show" -> showAll(p, list);
            case "clear" -> {
                saveCheckpoints(new ArrayList<>());
                p.sendMessage(m("elytrarace.checkpoint-cleared"));
            }
            default -> p.sendMessage(m("elytrarace.checkpoint-usage"));
        }
        return true;
    }

    /** Leest het nummer (1..max) uit args[1]; stuurt een melding en geeft null bij een fout. */
    private Integer number(Player p, String[] args, int max) {
        if (max <= 0) {
            p.sendMessage(m("elytrarace.checkpoint-none"));
            return null;
        }
        try {
            int nr = Integer.parseInt(args.length > 1 ? args[1] : "");
            if (nr >= 1 && nr <= max) return nr;
        } catch (NumberFormatException ignored) {
            // valt door naar de melding hieronder
        }
        p.sendMessage(m("elytrarace.checkpoint-number", "max", max));
        return null;
    }

    /** Laat alle checkpoints (groen) en de finish (geel) 10 seconden zien aan de bouwer. */
    private void showAll(Player p, List<Location> list) {
        double cpRadius = Math.max(1.0, plugin.getConfig().getDouble(ID + ".checkpoint-radius", 6.0));
        double fRadius = Math.max(1.0, plugin.getConfig().getDouble(ID + ".finish-radius", 8.0));
        Location fin = plugin.getConfig().getLocation(ID + ".finish");
        p.sendMessage(m("elytrarace.checkpoint-show", "count", list.size()));

        int[] runs = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || runs[0]++ >= 20) {
                task[0].cancel();
                return;
            }
            for (Location cp : list) drawRing(p, cp, cpRadius, Color.LIME);
            if (fin != null) drawRing(p, fin, fRadius, Color.YELLOW);
        }, 0L, 10L);
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    private boolean inEvent(Entity entity) {
        return entity instanceof Player p && isParticipant(p);
    }

    private boolean waitingAtStart(Player p) {
        return state == EventState.RUNNING && phase == Phase.PREP && racers.contains(p.getUniqueId());
    }

    /** Tijdens de startcountdown sta je stil (rondkijken mag). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!waitingAtStart(e.getPlayer())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && waitingAtStart(p)) e.setCancelled(true);
    }

    /** Geen vuurpijlen verspillen voor het startsein. */
    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL || !waitingAtStart(e.getPlayer())) return;
        if (e.getItem() != null && e.getItem().getType() == Material.FIREWORK_ROCKET) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent e) {
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
