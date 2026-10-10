package nl.eventcore.event.tnttag;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import nl.eventcore.EventCorePlugin;
import nl.eventcore.event.EventState;
import nl.eventcore.event.GameEvent;
import nl.eventcore.event.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * TNT Tag.
 * Deel 1: opzet, lobby, countdown, start.
 * Deel 2: tikken, lont + ontploffing, rondes, eliminatie, winnaar.
 * Deel 3 (dit): bossbar, beloningen, messages.yml. (Automatisch plannen zit in EventScheduler.)
 *
 * Varianten (zoals Hot Potato) extenden deze klasse; config en teksten staan dan onder hun eigen id,
 * en ze kunnen taggerCount, fuseLength, showFuseTime en tagItem overschrijven.
 */
public class TntTagEvent extends GameEvent implements Listener {

    public static final String ID = "tnttag";

    private enum Phase { PAUSE, FUSE }

    // spelverloop
    private final Set<UUID> taggers = new HashSet<>();
    private final Set<UUID> alive = new HashSet<>();
    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask endTask;
    private BossBar bar;
    private Phase phase = Phase.PAUSE;
    private int lobbyLeft;
    private int counter;
    private int round;
    private int startedWith;
    private boolean gameOver;

    // config
    private Location lobby;
    private Location spawn;
    private int minPlayers;
    private int maxPlayers;
    private int lobbyCountdown;
    private int taggerPercent;
    private int fuseSeconds;
    private int roundFuse;
    private int pauseSeconds;
    private long tagCooldownMs;
    private int winDelaySeconds;
    private int voidDepth;

    public TntTagEvent(EventCorePlugin plugin) {
        this(plugin, ID, "TNT Tag");
    }

    protected TntTagEvent(EventCorePlugin plugin, String id, String displayName) {
        super(plugin, id, displayName);
    }

    // ---------- berichten ----------

    /** Bericht met prefix (chat), uit messages.yml onder het id van dit event (tnttag.*, hotpotato.*). */
    protected final Component m(String key, Object... kv) {
        return plugin.messages().prefixed(getId() + "." + key, kv);
    }

    /** Bericht zonder prefix (titels, actionbar, bossbar, item). */
    protected final Component r(String key, Object... kv) {
        return plugin.messages().get(getId() + "." + key, kv);
    }

    // ---------- haakjes voor varianten ----------

    /** Hoeveel spelers het item krijgen. TNT Tag: tagger-percent van de levende spelers. */
    protected int taggerCount(int alive) {
        return alive * taggerPercent / 100;
    }

    /** Lengte van de lont deze ronde, in seconden. TNT Tag: altijd fuse-seconds. */
    protected int fuseLength() {
        return fuseSeconds;
    }

    /** Mag iedereen zien hoe lang de lont nog is? (bossbar-balk en tikgeluid) */
    protected boolean showFuseTime() {
        return true;
    }

    /** Het item dat de tagger vasthoudt en op zijn hoofd heeft. */
    protected ItemStack tagItem() {
        return new ItemStack(Material.TNT);
    }

    // ------------------------------------------------------------------
    // Validatie & openen
    // ------------------------------------------------------------------

    @Override
    public Optional<Component> validate() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getLocation(getId() + ".lobby") == null) return Optional.of(m("lobby-not-set"));
        if (cfg.getLocation(getId() + ".spawn") == null) return Optional.of(m("spawn-not-set"));
        return Optional.empty();
    }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        lobby = cfg.getLocation(getId() + ".lobby");
        spawn = cfg.getLocation(getId() + ".spawn");
        minPlayers = Math.max(2, cfg.getInt(getId() + ".min-players", 4));
        maxPlayers = Math.max(minPlayers, cfg.getInt(getId() + ".max-players", 30));
        lobbyCountdown = Math.max(5, cfg.getInt(getId() + ".lobby-countdown", 30));
        taggerPercent = Math.max(1, Math.min(100, cfg.getInt(getId() + ".tagger-percent", 50)));
        fuseSeconds = Math.max(5, cfg.getInt(getId() + ".fuse-seconds", 20));
        pauseSeconds = Math.max(3, cfg.getInt(getId() + ".round-pause-seconds", 5));
        tagCooldownMs = Math.max(0, cfg.getLong(getId() + ".tag-cooldown-ms", 1500L));
        winDelaySeconds = Math.max(3, cfg.getInt(getId() + ".win-delay-seconds", 8));
        voidDepth = Math.max(5, cfg.getInt(getId() + ".void-depth", 30));

        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        state = EventState.LOBBY;
        updateBar();

        announce(m("opened", "min", minPlayers));
    }

    // ------------------------------------------------------------------
    // Join / leave
    // ------------------------------------------------------------------

    @Override
    protected void onJoin(Player player) {
        if (participants.size() > maxPlayers) {
            player.sendMessage(m("full"));
            leave(player);
            return;
        }

        player.teleport(lobby);
        player.showBossBar(bar);
        broadcastToParticipants(m("joined", "player", player.getName(),
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
        boolean wasTagger = taggers.remove(id);
        boolean wasAlive = alive.remove(id);
        immuneUntil.remove(id);

        if (state == EventState.COUNTDOWN && participants.size() < minPlayers) {
            cancelCountdown();
            state = EventState.LOBBY;
            broadcastToParticipants(m("countdown-cancelled"));
            updateBar();
            return;
        }

        if (state == EventState.RUNNING && !gameOver && wasAlive) {
            broadcastToParticipants(m("player-left", "player", player.getName()));

            // Een tagger die weggaat: geef de TNT door aan iemand anders, zodat de ronde door kan.
            if (wasTagger && phase == Phase.FUSE && alive.size() >= 2) {
                List<Player> candidates = new ArrayList<>();
                for (Player p : alivePlayers()) {
                    if (!taggers.contains(p.getUniqueId())) candidates.add(p);
                }
                if (!candidates.isEmpty()) {
                    Collections.shuffle(candidates);
                    makeTagger(candidates.get(0));
                }
            }
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
                bar.name(r("bossbar-lobby-waiting", "count", participants.size(), "min", minPlayers));
                bar.color(BossBar.Color.YELLOW);
                bar.progress(clamp((float) participants.size() / minPlayers));
            }
            case COUNTDOWN -> {
                bar.name(r("bossbar-lobby-countdown", "seconds", lobbyLeft));
                bar.color(BossBar.Color.GREEN);
                bar.progress(clamp((float) lobbyLeft / lobbyCountdown));
            }
            case RUNNING -> {
                if (phase == Phase.FUSE) {
                    bar.name(r("bossbar-fuse", "round", round, "seconds", counter, "alive", alive.size()));
                    bar.color(BossBar.Color.RED);
                    // Verborgen lont: de balk blijft vol, zodat je niet kunt zien wanneer hij afgaat.
                    bar.progress(showFuseTime() ? clamp((float) counter / roundFuse) : 1f);
                } else {
                    bar.name(r("bossbar-pause", "round", round + 1, "seconds", counter, "alive", alive.size()));
                    bar.color(BossBar.Color.GREEN);
                    bar.progress(clamp((float) counter / pauseSeconds));
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
        round = 0;
        alive.clear();
        taggers.clear();
        immuneUntil.clear();

        for (Player p : players()) {
            alive.add(p.getUniqueId());
            p.teleport(spawn);
            p.showTitle(Title.title(
                    r("title-start"),
                    r("subtitle-start"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        }
        startedWith = alive.size();

        // Korte pauze voor ronde 1, daarna start de spelloop.
        phase = Phase.PAUSE;
        counter = pauseSeconds;
        updateBar();
        gameTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ------------------------------------------------------------------
    // Spelloop (1x per seconde)
    // ------------------------------------------------------------------

    private void tick() {
        if (gameOver || state != EventState.RUNNING) return;

        keepInArena();
        updateBar();

        switch (phase) {
            case PAUSE -> {
                if (counter <= 0) startRound();
                else counter--;
            }
            case FUSE -> {
                showFuse();
                if (counter <= 0) explode();
                else counter--;
            }
        }
    }

    private void startRound() {
        round++;
        phase = Phase.FUSE;
        roundFuse = Math.max(1, fuseLength());
        counter = roundFuse;

        taggers.clear();
        pickTaggers();
        broadcastToParticipants(m("round-start", "round", round, "alive", alive.size()));
        updateBar();
    }

    private void showFuse() {
        for (Player p : players()) {
            UUID id = p.getUniqueId();
            if (!alive.contains(id)) {
                p.sendActionBar(r("actionbar-spectator"));
            } else if (taggers.contains(id)) {
                p.sendActionBar(r("actionbar-tagger", "seconds", counter));
                if (!showFuseTime()) {
                    // Altijd hetzelfde tikje: spannend, maar het verraadt niets.
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 1f);
                    p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.01);
                } else if (counter <= 5) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 2f - (counter * 0.2f));
                }
            } else {
                p.sendActionBar(r("actionbar-runner"));
            }
        }
    }

    private void explode() {
        List<Player> exploding = new ArrayList<>();
        for (Player p : alivePlayers()) {
            if (taggers.contains(p.getUniqueId())) exploding.add(p);
        }

        for (Player p : exploding) {
            Location loc = p.getLocation();
            World world = loc.getWorld();
            world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
            world.spawnParticle(Particle.FLAME, loc, 40, 0.5, 0.8, 0.5, 0.05);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1f);
            eliminate(p);
            broadcastToParticipants(m("exploded", "player", p.getName()));
        }
        taggers.clear();

        if (checkWinner()) return;

        phase = Phase.PAUSE;
        counter = pauseSeconds;
        updateBar();
    }

    /** Speler is af: wordt toeschouwer. Blijft deelnemer tot het event eindigt. */
    private void eliminate(Player p) {
        UUID id = p.getUniqueId();
        alive.remove(id);
        taggers.remove(id);
        clearTagger(p);

        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(spawn.clone().add(0, 3, 0));
        p.showTitle(Title.title(
                r("eliminated-title"),
                r("eliminated-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
    }

    /** Valt iemand uit de arena (te diep), dan terug naar de spawn. */
    private void keepInArena() {
        double minY = spawn.getY() - voidDepth;
        for (Player p : alivePlayers()) {
            if (p.getLocation().getY() < minY) {
                p.teleport(spawn);
                p.setFallDistance(0);
            }
        }
    }

    // ------------------------------------------------------------------
    // Winnaar & beloningen
    // ------------------------------------------------------------------

    /** Geeft true terug als het event afgelopen is. */
    private boolean checkWinner() {
        if (gameOver || state != EventState.RUNNING) return gameOver;
        if (alive.size() > 1) return false;

        gameOver = true;
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }

        Player winner = alive.isEmpty() ? null : Bukkit.getPlayer(alive.iterator().next());

        if (winner != null) {
            clearTagger(winner);
            announce(m("winner-broadcast", "player", winner.getName()));
            for (Player p : players()) {
                p.showTitle(Title.title(
                        r("winner-title", "player", winner.getName()),
                        r("winner-subtitle", "player", winner.getName()),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            launchFirework(winner.getLocation());
            bar.name(r("bossbar-winner", "player", winner.getName()));
        } else {
            announce(m("no-winner"));
            bar.name(r("bossbar-nowinner"));
        }
        bar.color(BossBar.Color.PURPLE);
        bar.progress(1f);

        giveRewards(winner);

        endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> stop(false), winDelaySeconds * 20L);
        return true;
    }

    private void giveRewards(Player winner) {
        RewardService.give(plugin, getId(), startedWith, winner, players());
    }

    private void launchFirework(Location loc) {
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

    // ------------------------------------------------------------------
    // TNT-spelers
    // ------------------------------------------------------------------

    private void pickTaggers() {
        List<Player> pool = alivePlayers();
        Collections.shuffle(pool);

        // Minimaal 1 tagger, maar er moet altijd minstens 1 speler zonder TNT overblijven.
        int count = Math.min(Math.max(1, taggerCount(pool.size())), Math.max(1, pool.size() - 1));
        for (int i = 0; i < count && i < pool.size(); i++) {
            makeTagger(pool.get(i));
        }
        broadcastToParticipants(m("taggers-picked", "count", count));
    }

    /** Geeft een speler de TNT (helm + hotbar), snelheid en laat hem oplichten. */
    public void makeTagger(Player player) {
        taggers.add(player.getUniqueId());

        ItemStack tnt = tagItem();
        ItemMeta meta = tnt.getItemMeta();
        meta.displayName(r("tnt-name").decoration(TextDecoration.ITALIC, false));
        tnt.setItemMeta(meta);

        player.getInventory().setHelmet(tnt);
        for (int slot = 0; slot < 9; slot++) {
            player.getInventory().setItem(slot, tnt);
        }
        player.setGlowing(true);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false));
        player.sendMessage(m("you-are-tagger"));
        player.playSound(player.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, showFuseTime() ? 1f : 1.6f);
    }

    /** Haalt de TNT weg bij een speler. */
    public void clearTagger(Player player) {
        taggers.remove(player.getUniqueId());
        player.getInventory().setHelmet(null);
        for (int slot = 0; slot < 9; slot++) {
            player.getInventory().setItem(slot, null);
        }
        player.setGlowing(false);
        player.removePotionEffect(PotionEffectType.SPEED);
    }

    public boolean isTagger(Player player) {
        return taggers.contains(player.getUniqueId());
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
    // Tikken
    // ------------------------------------------------------------------

    private void handleTag(Player attacker, Player victim) {
        if (state != EventState.RUNNING || gameOver || phase != Phase.FUSE) return;

        UUID a = attacker.getUniqueId();
        UUID v = victim.getUniqueId();
        if (!alive.contains(a) || !alive.contains(v)) return;
        if (!taggers.contains(a) || taggers.contains(v)) return;

        long now = System.currentTimeMillis();
        if (immuneUntil.getOrDefault(v, 0L) > now) {
            attacker.sendActionBar(r("immune"));
            return;
        }

        clearTagger(attacker);
        makeTagger(victim);
        immuneUntil.put(a, now + tagCooldownMs);

        attacker.sendMessage(m("tagged-attacker", "player", victim.getName()));
        victim.sendMessage(m("tagged-victim", "player", attacker.getName()));
        attacker.playSound(attacker.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
        victim.getWorld().spawnParticle(Particle.LAVA, victim.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3);
    }

    // ------------------------------------------------------------------
    // Afsluiten
    // ------------------------------------------------------------------

    @Override
    protected void onStop(boolean forced) {
        cancelCountdown();
        if (gameTask != null) { gameTask.cancel(); gameTask = null; }
        if (endTask != null) { endTask.cancel(); endTask = null; }

        if (bar != null) {
            for (Player p : players()) p.hideBossBar(bar);
        }
        taggers.clear();
        alive.clear();
        immuneUntil.clear();
        gameOver = true;
        HandlerList.unregisterAll(this);

        if (forced) announce(m("stopped"));
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    private boolean inEvent(Entity entity) {
        return entity instanceof Player p && isParticipant(p);
    }

    /**
     * Schade is altijd uit. Tikken gaat via een klap tussen twee spelers.
     * ignoreCancelled staat bewust op false: ook als een ander plugin (bv. WorldGuard) PvP blokkeert,
     * moet het tikken nog werken.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent e) {
        if (!inEvent(e.getEntity())) return;

        if (e instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player attacker
                && e.getEntity() instanceof Player victim
                && isParticipant(attacker)) {
            handleTag(attacker, victim);
        }
        e.setCancelled(true);
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
