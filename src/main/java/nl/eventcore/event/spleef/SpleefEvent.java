package nl.eventcore.event.spleef;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Spleef: hak de blokken onder je tegenstanders weg. Wie valt, is af. De laatste speler wint.
 *
 * Arena: de vloer wordt door jullie zelf gebouwd. Met /event setup spleef pos1 en pos2 geef je het
 * gebied aan (kijk naar een blok). Bij de start wordt de vloer onthouden en na het event hersteld.
 */
public final class SpleefEvent extends GameEvent implements Listener {

    public static final String ID = "spleef";

    private enum Phase { PREP, PLAY }

    // spelverloop
    private final Set<UUID> alive = new HashSet<>();
    private final List<Block> decayPool = new ArrayList<>();
    private final Random random = new Random();
    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask fallTask;
    private BukkitTask endTask;
    private BossBar bar;
    private Phase phase = Phase.PREP;
    private int lobbyLeft;
    private int counter;
    private int elapsed;
    private int startedWith;
    private boolean gameOver;
    private boolean decayAnnounced;

    // arena
    private Location lobby;
    private Location spawn;
    private FloorArena arena;

    // config
    private int minPlayers;
    private int maxPlayers;
    private int lobbyCountdown;
    private int startDelay;
    private int winDelaySeconds;
    private boolean giveShovel;
    private boolean decayEnabled;
    private int decayStart;
    private int decayInterval;
    private int decayBlocks;

    public SpleefEvent(EventCorePlugin plugin) {
        super(plugin, ID, "Spleef");
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
        if (cfg.getLocation(ID + ".lobby") == null) return Optional.of(m("spleef.lobby-not-set"));
        if (cfg.getLocation(ID + ".spawn") == null) return Optional.of(m("spleef.spawn-not-set"));

        Location p1 = cfg.getLocation(ID + ".pos1");
        Location p2 = cfg.getLocation(ID + ".pos2");
        if (p1 == null || p2 == null) return Optional.of(m("spleef.region-not-set"));
        if (p1.getWorld() == null || !p1.getWorld().equals(p2.getWorld())) return Optional.of(m("spleef.region-world"));

        long volume = FloorArena.volume(p1, p2);
        long max = cfg.getLong(ID + ".max-region-volume", 200000L);
        if (volume > max) return Optional.of(m("spleef.region-too-big", "volume", volume, "max", max));

        return Optional.empty();
    }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        lobby = cfg.getLocation(ID + ".lobby");
        spawn = cfg.getLocation(ID + ".spawn");
        Set<Material> breakable = FloorArena.parseMaterials(FloorArena.readNames(cfg, ID + ".breakable-blocks"),
                Material.SNOW_BLOCK, plugin.getLogger(), "Spleef breakable-blocks");
        arena = new FloorArena(cfg.getLocation(ID + ".pos1"), cfg.getLocation(ID + ".pos2"), breakable, plugin.getLogger());

        minPlayers = Math.max(2, cfg.getInt(ID + ".min-players", 4));
        maxPlayers = Math.max(minPlayers, cfg.getInt(ID + ".max-players", 30));
        lobbyCountdown = Math.max(5, cfg.getInt(ID + ".lobby-countdown", 30));
        startDelay = Math.max(3, cfg.getInt(ID + ".start-delay-seconds", 5));
        winDelaySeconds = Math.max(3, cfg.getInt(ID + ".win-delay-seconds", 8));
        giveShovel = cfg.getBoolean(ID + ".give-shovel", true);
        decayEnabled = cfg.getBoolean(ID + ".decay.enabled", true);
        decayStart = Math.max(0, cfg.getInt(ID + ".decay.start-seconds", 60));
        decayInterval = Math.max(1, cfg.getInt(ID + ".decay.interval-seconds", 3));
        decayBlocks = Math.max(1, cfg.getInt(ID + ".decay.blocks-per-step", 5));

        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        state = EventState.LOBBY;
        updateBar();

        announce(m("spleef.opened", "min", minPlayers));
    }

    // ------------------------------------------------------------------
    // Join / leave
    // ------------------------------------------------------------------

    @Override
    protected void onJoin(Player player) {
        if (participants.size() > maxPlayers) {
            player.sendMessage(m("spleef.full"));
            leave(player);
            return;
        }

        player.teleport(lobby);
        player.showBossBar(bar);
        broadcastToParticipants(m("spleef.joined", "player", player.getName(),
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

        if (state == EventState.COUNTDOWN && participants.size() < minPlayers) {
            cancelCountdown();
            state = EventState.LOBBY;
            broadcastToParticipants(m("spleef.countdown-cancelled"));
            updateBar();
            return;
        }

        if (state == EventState.RUNNING && !gameOver && wasAlive) {
            broadcastToParticipants(m("spleef.player-left", "player", player.getName()));
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
                bar.name(r("spleef.bossbar-lobby-waiting", "count", participants.size(), "min", minPlayers));
                bar.color(BossBar.Color.YELLOW);
                bar.progress(clamp((float) participants.size() / minPlayers));
            }
            case COUNTDOWN -> {
                bar.name(r("spleef.bossbar-lobby-countdown", "seconds", lobbyLeft));
                bar.color(BossBar.Color.GREEN);
                bar.progress(clamp((float) lobbyLeft / lobbyCountdown));
            }
            case RUNNING -> {
                if (phase == Phase.PREP) {
                    bar.name(r("spleef.bossbar-prep", "seconds", counter));
                    bar.color(BossBar.Color.GREEN);
                    bar.progress(clamp((float) counter / startDelay));
                } else {
                    String time = String.format("%d:%02d", elapsed / 60, elapsed % 60);
                    bar.name(r("spleef.bossbar-play", "alive", alive.size(), "time", time));
                    bar.color(BossBar.Color.BLUE);
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
        decayAnnounced = false;
        elapsed = 0;
        alive.clear();

        arena.takeSnapshot(arenaBackupFile());
        decayPool.clear();
        decayPool.addAll(arena.breakableBlocks());
        List<Location> spots = arena.findSpots();

        int i = 0;
        for (Player p : players()) {
            alive.add(p.getUniqueId());

            Location spot = spots.isEmpty() ? spawn : spots.get(i % spots.size());
            i++;
            p.teleport(spot);
            p.setGameMode(GameMode.SURVIVAL);
            if (giveShovel) giveShovel(p);

            p.showTitle(Title.title(
                    r("spleef.title-start"),
                    r("spleef.subtitle-start"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
        }
        startedWith = alive.size();

        // Korte voorbereiding: nog niet slaan.
        phase = Phase.PREP;
        counter = startDelay;
        updateBar();
        gameTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        fallTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkFalls, 4L, 4L);
    }

    private void giveShovel(Player p) {
        ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        meta.displayName(r("spleef.shovel-name").decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        shovel.setItemMeta(meta);
        p.getInventory().setItem(0, shovel);
        p.getInventory().setHeldItemSlot(0);
    }

    // ------------------------------------------------------------------
    // Vloer: onthouden, spreiden, vervallen, herstellen
    // ------------------------------------------------------------------

    /** Laat willekeurige blokken verdwijnen zodat niemand eindeloos kan blijven wachten. */
    private void decayStep() {
        for (int i = 0; i < decayBlocks && !decayPool.isEmpty(); i++) {
            int idx = random.nextInt(decayPool.size());
            Block b = decayPool.get(idx);
            decayPool.set(idx, decayPool.get(decayPool.size() - 1));
            decayPool.remove(decayPool.size() - 1);

            if (!arena.isBreakable(b.getType())) continue;
            b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, b.getBlockData());
            b.setType(Material.AIR, false);
        }
    }

    // ------------------------------------------------------------------
    // Spelloop
    // ------------------------------------------------------------------

    private void tick() {
        if (gameOver || state != EventState.RUNNING) return;

        if (phase == Phase.PREP) {
            updateBar();
            if (counter <= 0) {
                phase = Phase.PLAY;
                elapsed = 0;
                for (Player p : players()) {
                    p.showTitle(Title.title(
                            r("spleef.go-title"), r("spleef.go-subtitle"),
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
        if (decayEnabled && elapsed >= decayStart && (elapsed - decayStart) % decayInterval == 0) {
            if (!decayAnnounced) {
                decayAnnounced = true;
                broadcastToParticipants(m("spleef.decay-start"));
            }
            decayStep();
        }
        updateBar();
    }

    /** Valt iemand onder de vloer, dan is hij af. Controleert vaker dan 1x per seconde. */
    private void checkFalls() {
        if (gameOver || state != EventState.RUNNING) return;

        for (Player p : alivePlayers()) {
            if (p.getLocation().getY() < arena.minY()) eliminate(p);
        }
        checkWinner();
    }

    private void eliminate(Player p) {
        alive.remove(p.getUniqueId());

        p.setGameMode(GameMode.SPECTATOR);
        p.setFallDistance(0);
        p.teleport(spawn);
        p.showTitle(Title.title(
                r("spleef.eliminated-title"),
                r("spleef.eliminated-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        broadcastToParticipants(m("spleef.out", "player", p.getName(), "alive", alive.size()));
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
        if (fallTask != null) { fallTask.cancel(); fallTask = null; }

        Player winner = alive.isEmpty() ? null : Bukkit.getPlayer(alive.iterator().next());

        if (winner != null) {
            announce(m("spleef.winner-broadcast", "player", winner.getName()));
            for (Player p : players()) {
                p.showTitle(Title.title(
                        r("spleef.winner-title", "player", winner.getName()),
                        r("spleef.winner-subtitle", "player", winner.getName()),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            celebrate(winner.getLocation());
            bar.name(r("spleef.bossbar-winner", "player", winner.getName()));
        } else {
            announce(m("spleef.no-winner"));
            bar.name(r("spleef.bossbar-nowinner"));
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
        if (fallTask != null) { fallTask.cancel(); fallTask = null; }
        if (endTask != null) { endTask.cancel(); endTask = null; }

        if (bar != null) {
            for (Player p : players()) p.hideBossBar(bar);
        }
        alive.clear();
        gameOver = true;
        HandlerList.unregisterAll(this);

        // Vloer terugzetten. Gebeurt direct, zodat het ook werkt tijdens het afsluiten van de server.
        decayPool.clear();
        if (arena != null) arena.restore();

        if (forced) announce(m("spleef.stopped"));
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    private boolean inEvent(Entity entity) {
        return entity instanceof Player p && isParticipant(p);
    }

    private boolean canBreak(Player p, Block b) {
        return state == EventState.RUNNING && !gameOver && phase == Phase.PLAY
                && alive.contains(p.getUniqueId())
                && arena.contains(b) && arena.isBreakable(b.getType());
    }

    /**
     * Blokken breken: alleen breekbare vloerblokken in het gebied, en alleen tijdens het spel.
     * ignoreCancelled staat bewust op false: ook als een beschermplugin (bv. WorldGuard) de arena
     * dichthoudt, moet spleef werken.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!isParticipant(p)) return;

        if (canBreak(p, e.getBlock())) {
            e.setCancelled(false);
            e.setDropItems(false);
            e.setExpToDrop(0);
        } else {
            e.setCancelled(true);
        }
    }

    /** Blokken breken meteen, zoals bij spleef hoort. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent e) {
        Player p = e.getPlayer();
        if (!isParticipant(p)) return;

        if (canBreak(p, e.getBlock())) {
            e.setCancelled(false);
            e.setInstaBreak(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent e) {
        if (isParticipant(e.getPlayer())) e.setCancelled(true);
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
}
