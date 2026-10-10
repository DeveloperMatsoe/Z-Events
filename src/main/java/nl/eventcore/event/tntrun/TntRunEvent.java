package nl.eventcore.event.tntrun;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import nl.eventcore.EventCorePlugin;
import nl.eventcore.event.EventState;
import nl.eventcore.event.GameEvent;
import nl.eventcore.event.RewardService;
import nl.eventcore.event.arena.FloorArena;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * TNT Run: de vloer verdwijnt onder je voeten. Blijf rennen; wie valt is af, de laatste wint.
 *
 * Arena: bouw zelf een vloer (klassiek: zand/gravel boven TNT) en geef het gebied aan met
 * /event setup tntrun pos1 en pos2. De vloer wordt na afloop hersteld.
 */
public final class TntRunEvent extends GameEvent implements Listener {

    public static final String ID = "tntrun";

    private enum Phase { PREP, PLAY }

    private record Pending(int due, int x, int y, int z) {}

    // spelverloop
    private final Set<UUID> alive = new HashSet<>();
    private final Map<UUID, Integer> jumps = new HashMap<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Set<Long> scheduled = new HashSet<>();
    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask stepTask;
    private BukkitTask endTask;
    private BossBar bar;
    private Phase phase = Phase.PREP;
    private int lobbyLeft;
    private int counter;
    private int elapsed;
    private int stepCounter;
    private int startedWith;
    private boolean gameOver;

    // arena
    private Location lobby;
    private Location spawn;
    private FloorArena arena;
    private Set<Material> removeBelow;

    // config
    private int minPlayers;
    private int maxPlayers;
    private int lobbyCountdown;
    private int startDelay;
    private int winDelaySeconds;
    private int removeDelaySteps;
    private int maxJumps;

    public TntRunEvent(EventCorePlugin plugin) {
        super(plugin, ID, "TNT Run");
    }

    @Override
    public List<String> setupKeys() {
        return List.of("lobby", "spawn", "pos1", "pos2");
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
        if (cfg.getLocation(ID + ".lobby") == null) return Optional.of(m("tntrun.lobby-not-set"));
        if (cfg.getLocation(ID + ".spawn") == null) return Optional.of(m("tntrun.spawn-not-set"));

        Location p1 = cfg.getLocation(ID + ".pos1");
        Location p2 = cfg.getLocation(ID + ".pos2");
        if (p1 == null || p2 == null) return Optional.of(m("tntrun.region-not-set"));
        if (p1.getWorld() == null || !p1.getWorld().equals(p2.getWorld())) return Optional.of(m("tntrun.region-world"));

        long volume = FloorArena.volume(p1, p2);
        long max = cfg.getLong(ID + ".max-region-volume", 200000L);
        if (volume > max) return Optional.of(m("tntrun.region-too-big", "volume", volume, "max", max));

        return Optional.empty();
    }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        lobby = cfg.getLocation(ID + ".lobby");
        spawn = cfg.getLocation(ID + ".spawn");

        Set<Material> breakable = FloorArena.parseMaterials(FloorArena.readNames(cfg, ID + ".breakable-blocks"),
                Material.SAND, plugin.getLogger(), "TNT Run breakable-blocks");
        removeBelow = FloorArena.parseMaterials(FloorArena.readNames(cfg, ID + ".remove-below-materials"),
                null, plugin.getLogger(), "TNT Run remove-below-materials");
        plugin.getLogger().info("TNT Run: vloer = " + breakable + ", verdwijnt eronder mee = "
                + (removeBelow.isEmpty() ? "niets" : removeBelow));
        arena = new FloorArena(cfg.getLocation(ID + ".pos1"), cfg.getLocation(ID + ".pos2"), breakable, plugin.getLogger());

        minPlayers = Math.max(2, cfg.getInt(ID + ".min-players", 4));
        maxPlayers = Math.max(minPlayers, cfg.getInt(ID + ".max-players", 30));
        lobbyCountdown = Math.max(5, cfg.getInt(ID + ".lobby-countdown", 30));
        startDelay = Math.max(3, cfg.getInt(ID + ".start-delay-seconds", 5));
        winDelaySeconds = Math.max(3, cfg.getInt(ID + ".win-delay-seconds", 8));
        // De loop draait elke 2 ticks, dus de vertraging rekenen we om naar stappen.
        removeDelaySteps = Math.max(1, cfg.getInt(ID + ".remove-delay-ticks", 10) / 2);
        maxJumps = Math.max(0, cfg.getInt(ID + ".double-jumps", 3));

        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        state = EventState.LOBBY;
        updateBar();

        announce(m("tntrun.opened", "min", minPlayers));
    }

    // ------------------------------------------------------------------
    // Join / leave
    // ------------------------------------------------------------------

    @Override
    protected void onJoin(Player player) {
        if (participants.size() > maxPlayers) {
            player.sendMessage(m("tntrun.full"));
            leave(player);
            return;
        }

        player.teleport(lobby);
        player.showBossBar(bar);
        broadcastToParticipants(m("tntrun.joined", "player", player.getName(),
                "count", participants.size(), "max", maxPlayers));

        if (state == EventState.LOBBY && participants.size() >= minPlayers) {
            startCountdown();
        }
        updateBar();
    }

    @Override
    protected void onLeave(Player player) {
        player.hideBossBar(bar);

        boolean wasAlive = alive.remove(player.getUniqueId());
        jumps.remove(player.getUniqueId());

        if (state == EventState.COUNTDOWN && participants.size() < minPlayers) {
            cancelCountdown();
            state = EventState.LOBBY;
            broadcastToParticipants(m("tntrun.countdown-cancelled"));
            updateBar();
            return;
        }

        if (state == EventState.RUNNING && !gameOver && wasAlive) {
            broadcastToParticipants(m("tntrun.player-left", "player", player.getName()));
            checkWinner();
        }
        updateBar();
    }

    // ------------------------------------------------------------------
    // Bossbar
    // ------------------------------------------------------------------

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void updateBar() {
        if (bar == null || gameOver) return;

        switch (state) {
            case LOBBY -> {
                bar.name(r("tntrun.bossbar-lobby-waiting", "count", participants.size(), "min", minPlayers));
                bar.color(BossBar.Color.YELLOW);
                bar.progress(clamp((float) participants.size() / minPlayers));
            }
            case COUNTDOWN -> {
                bar.name(r("tntrun.bossbar-lobby-countdown", "seconds", lobbyLeft));
                bar.color(BossBar.Color.GREEN);
                bar.progress(clamp((float) lobbyLeft / lobbyCountdown));
            }
            case RUNNING -> {
                if (phase == Phase.PREP) {
                    bar.name(r("tntrun.bossbar-prep", "seconds", counter));
                    bar.color(BossBar.Color.GREEN);
                    bar.progress(clamp((float) counter / startDelay));
                } else {
                    String time = String.format("%d:%02d", elapsed / 60, elapsed % 60);
                    bar.name(r("tntrun.bossbar-play", "alive", alive.size(), "time", time));
                    bar.color(BossBar.Color.RED);
                    bar.progress(clamp(startedWith == 0 ? 0f : (float) alive.size() / startedWith));
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
        elapsed = 0;
        stepCounter = 0;
        alive.clear();
        jumps.clear();
        queue.clear();
        scheduled.clear();

        arena.takeSnapshot(arenaBackupFile());
        List<Location> spots = arena.findSpots();

        int i = 0;
        for (Player p : players()) {
            alive.add(p.getUniqueId());
            jumps.put(p.getUniqueId(), maxJumps);

            Location spot = spots.isEmpty() ? spawn : spots.get(i % spots.size());
            i++;
            p.teleport(spot);
            p.setGameMode(GameMode.SURVIVAL);

            p.showTitle(Title.title(
                    r("tntrun.title-start"),
                    r("tntrun.subtitle-start"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        }
        startedWith = alive.size();

        // Korte voorbereiding: de vloer is nog veilig.
        phase = Phase.PREP;
        counter = startDelay;
        updateBar();
        gameTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        stepTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::step, 2L, 2L);
    }

    // ------------------------------------------------------------------
    // Spelloop
    // ------------------------------------------------------------------

    /** 1x per seconde: fases, bossbar en actionbar. */
    private void tick() {
        if (gameOver || state != EventState.RUNNING) return;

        if (phase == Phase.PREP) {
            updateBar();
            if (counter <= 0) {
                phase = Phase.PLAY;
                elapsed = 0;
                for (Player p : players()) {
                    if (alive.contains(p.getUniqueId()) && maxJumps > 0) p.setAllowFlight(true);
                    p.showTitle(Title.title(
                            r("tntrun.go-title"), r("tntrun.go-subtitle"),
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

        elapsed++;
        if (maxJumps > 0) {
            for (Player p : alivePlayers()) {
                p.sendActionBar(r("tntrun.actionbar-jumps", "jumps", jumps.getOrDefault(p.getUniqueId(), 0)));
            }
        }
        updateBar();
    }

    /** Elke 2 ticks: valt iemand? welke blokken staan onder spelers? welke verdwijnen nu? */
    private void step() {
        if (gameOver || state != EventState.RUNNING) return;
        stepCounter++;

        for (Player p : alivePlayers()) {
            if (p.getLocation().getY() < arena.minY()) {
                eliminate(p);
            } else if (phase == Phase.PLAY) {
                markUnder(p);
            }
        }

        while (!queue.isEmpty() && queue.peek().due() <= stepCounter) {
            removeBlock(queue.poll());
        }

        checkWinner();
    }

    private static final double[][] OFFSETS = {{0, 0}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}};

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    /** Plant de blokken onder de voeten van een speler in om te verdwijnen. */
    private void markUnder(Player p) {
        Location loc = p.getLocation();
        if (!arena.world().equals(loc.getWorld())) return;
        int by = (int) Math.floor(loc.getY() - 0.01);

        for (double[] off : OFFSETS) {
            int bx = (int) Math.floor(loc.getX() + off[0]);
            int bz = (int) Math.floor(loc.getZ() + off[1]);
            if (!arena.contains(bx, by, bz)) continue;

            long k = key(bx, by, bz);
            if (scheduled.contains(k)) continue;

            Block b = arena.world().getBlockAt(bx, by, bz);
            if (!arena.isBreakable(b.getType())) continue;

            scheduled.add(k);
            queue.add(new Pending(stepCounter + removeDelaySteps, bx, by, bz));
        }
    }

    private void removeBlock(Pending pending) {
        scheduled.remove(key(pending.x(), pending.y(), pending.z()));

        Block b = arena.world().getBlockAt(pending.x(), pending.y(), pending.z());
        if (!arena.isBreakable(b.getType())) return;

        arena.world().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, b.getBlockData());
        b.setType(Material.AIR, false);

        // Klassiek: het blok eronder (bv. TNT) verdwijnt mee.
        // Dit hoeft niet binnen pos1-pos2 te liggen; het wordt onthouden en na afloop hersteld.
        if (!removeBelow.isEmpty()) {
            Block below = arena.world().getBlockAt(pending.x(), pending.y() - 1, pending.z());
            if (removeBelow.contains(below.getType())) {
                arena.remember(below);
                below.setType(Material.AIR, false);
            }
        }
    }

    private void eliminate(Player p) {
        UUID id = p.getUniqueId();
        alive.remove(id);
        jumps.remove(id);

        p.setFlying(false);
        p.setAllowFlight(false);
        p.setGameMode(GameMode.SPECTATOR);
        p.setFallDistance(0);
        p.teleport(spawn);
        p.showTitle(Title.title(
                r("tntrun.eliminated-title"),
                r("tntrun.eliminated-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        broadcastToParticipants(m("tntrun.out", "player", p.getName(), "alive", alive.size()));
        updateBar();
    }

    private List<Player> alivePlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) list.add(p);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Winnaar
    // ------------------------------------------------------------------

    private boolean checkWinner() {
        if (gameOver || state != EventState.RUNNING) return gameOver;
        if (alive.size() > 1) return false;

        gameOver = true;
        if (gameTask != null) { gameTask.cancel(); gameTask = null; }
        if (stepTask != null) { stepTask.cancel(); stepTask = null; }

        Player winner = alive.isEmpty() ? null : Bukkit.getPlayer(alive.iterator().next());

        if (winner != null) {
            winner.setAllowFlight(false);
            announce(m("tntrun.winner-broadcast", "player", winner.getName()));
            for (Player p : players()) {
                p.showTitle(Title.title(
                        r("tntrun.winner-title", "player", winner.getName()),
                        r("tntrun.winner-subtitle", "player", winner.getName()),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            celebrate(winner.getLocation());
            bar.name(r("tntrun.bossbar-winner", "player", winner.getName()));
        } else {
            announce(m("tntrun.no-winner"));
            bar.name(r("tntrun.bossbar-nowinner"));
        }
        bar.color(BossBar.Color.PURPLE);
        bar.progress(1f);

        RewardService.give(plugin, ID, startedWith, winner, players());

        endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> stop(false), winDelaySeconds * 20L);
        return true;
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
        alive.clear();
        jumps.clear();
        queue.clear();
        scheduled.clear();
        gameOver = true;
        HandlerList.unregisterAll(this);

        // Vloer terugzetten. Gebeurt direct, zodat het ook werkt tijdens het afsluiten van de server.
        if (arena != null) arena.restore();

        if (forced) announce(m("tntrun.stopped"));
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    private boolean inEvent(Entity entity) {
        return entity instanceof Player p && isParticipant(p);
    }

    /** Dubbele sprong: dubbel op spatie tikken geeft een zetje, zolang je sprongen over hebt. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!isParticipant(p) || !alive.contains(id) || !e.isFlying()) return;

        e.setCancelled(true);
        p.setFlying(false);
        if (state != EventState.RUNNING || gameOver || phase != Phase.PLAY) return;

        int left = jumps.getOrDefault(id, 0);
        if (left <= 0) {
            p.setAllowFlight(false);
            return;
        }
        left--;
        jumps.put(id, left);

        Vector push = p.getLocation().getDirection();
        push.setY(0);
        if (push.lengthSquared() > 0.0001) push.normalize().multiply(0.4);
        else push = new Vector(0, 0, 0);
        push.setY(0.8);
        p.setVelocity(push);
        p.playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.8f, 1.4f);
        if (left <= 0) p.setAllowFlight(false);
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
