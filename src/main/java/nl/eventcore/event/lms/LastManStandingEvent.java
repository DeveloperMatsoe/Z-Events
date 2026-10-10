package nl.eventcore.event.lms;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import nl.eventcore.EventCorePlugin;
import nl.eventcore.event.EventState;
import nl.eventcore.event.GameEvent;
import nl.eventcore.event.RewardService;
import nl.eventcore.event.arena.FloorArena;
import nl.eventcore.util.ItemGiver;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Last Man Standing: PvP in een kleine arena, de laatste levende speler wint.
 *
 * Arena: geef het gebied aan met /event setup lms pos1 en pos2 (kijk naar een hoekblok),
 * plus lobby en spawn (spawn = waar uitgeschakelde spelers toekijken).
 * Iedereen krijgt de kit uit config.yml (lms.kit); tijdens het event kan een admin
 * extra items geven met /event give all <item> [plek].
 *
 * Varianten (zoals One Shot) extenden deze klasse; config en teksten staan dan onder hun eigen id,
 * en ze kunnen prepareFighter, isLethal en onKill overschrijven.
 */
public class LastManStandingEvent extends GameEvent implements Listener {

    public static final String ID = "lms";

    private enum Phase { PREP, PLAY }

    private record Hit(UUID attacker, long time) {}

    // spelverloop
    private final Set<UUID> alive = new HashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Hit> lastHit = new HashMap<>();
    private final Random random = new Random();
    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask endTask;
    private BossBar bar;
    private Phase phase = Phase.PREP;
    private int lobbyLeft;
    private int counter;
    private int elapsed;
    private int startedWith;
    private boolean gameOver;
    private boolean glowing;

    // arena
    private Location lobby;
    private Location spawn;
    private World world;
    private int minX, maxX, minY, maxY, minZ, maxZ;

    // config
    private int minPlayers;
    private int maxPlayers;
    private int lobbyCountdown;
    private int startDelay;
    private int winDelaySeconds;
    private int glowAfter;
    private boolean hunger;
    private boolean naturalRegen;
    private List<String> kit;

    public LastManStandingEvent(EventCorePlugin plugin) {
        this(plugin, ID, "Last Man Standing");
    }

    protected LastManStandingEvent(EventCorePlugin plugin, String id, String displayName) {
        super(plugin, id, displayName);
    }

    @Override
    public List<String> setupKeys() {
        return List.of("lobby", "spawn", "pos1", "pos2");
    }

    /** Tekst uit messages.yml onder het id van dit event (lms.*, oneshot.*), met prefix. */
    protected final Component m(String key, Object... kv) {
        return plugin.messages().prefixed(getId() + "." + key, kv);
    }

    /** Idem, zonder prefix. */
    protected final Component r(String key, Object... kv) {
        return plugin.messages().get(getId() + "." + key, kv);
    }

    // ------------------------------------------------------------------
    // Haakjes voor varianten
    // ------------------------------------------------------------------

    /** Na het geven van de kit, vlak voor de start. Bv. One Shot zet hier de levens op 1 hart. */
    protected void prepareFighter(Player player) {
    }

    /** Is deze klap dodelijk? Standaard: als de schade al je levens (plus absorptie) opmaakt. */
    protected boolean isLethal(Player victim, EntityDamageEvent e) {
        return e.getFinalDamage() >= victim.getHealth() + victim.getAbsorptionAmount();
    }

    /** Iemand heeft een kill gemaakt (de killer leeft nog). */
    protected void onKill(Player killer) {
    }

    // ------------------------------------------------------------------
    // Validatie & openen
    // ------------------------------------------------------------------

    @Override
    public Optional<Component> validate() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getLocation(getId() + ".lobby") == null) return Optional.of(m("lobby-not-set"));
        if (cfg.getLocation(getId() + ".spawn") == null) return Optional.of(m("spawn-not-set"));

        Location p1 = cfg.getLocation(getId() + ".pos1");
        Location p2 = cfg.getLocation(getId() + ".pos2");
        if (p1 == null || p2 == null) return Optional.of(m("region-not-set"));
        if (p1.getWorld() == null || !p1.getWorld().equals(p2.getWorld())) return Optional.of(m("region-world"));

        long volume = FloorArena.volume(p1, p2);
        long max = cfg.getLong(getId() + ".max-region-volume", 200000L);
        if (volume > max) return Optional.of(m("region-too-big", "volume", volume, "max", max));

        return Optional.empty();
    }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        lobby = cfg.getLocation(getId() + ".lobby");
        spawn = cfg.getLocation(getId() + ".spawn");

        Location p1 = cfg.getLocation(getId() + ".pos1");
        Location p2 = cfg.getLocation(getId() + ".pos2");
        world = p1.getWorld();
        minX = Math.min(p1.getBlockX(), p2.getBlockX());
        maxX = Math.max(p1.getBlockX(), p2.getBlockX());
        minY = Math.min(p1.getBlockY(), p2.getBlockY());
        maxY = Math.max(p1.getBlockY(), p2.getBlockY());
        minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
        maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());

        minPlayers = Math.max(2, cfg.getInt(getId() + ".min-players", 2));
        maxPlayers = Math.max(minPlayers, cfg.getInt(getId() + ".max-players", 20));
        lobbyCountdown = Math.max(5, cfg.getInt(getId() + ".lobby-countdown", 30));
        startDelay = Math.max(3, cfg.getInt(getId() + ".start-delay-seconds", 5));
        winDelaySeconds = Math.max(3, cfg.getInt(getId() + ".win-delay-seconds", 8));
        glowAfter = Math.max(0, cfg.getInt(getId() + ".glow-after-seconds", 120));
        hunger = cfg.getBoolean(getId() + ".hunger", false);
        naturalRegen = cfg.getBoolean(getId() + ".natural-regen", true);
        kit = cfg.getStringList(getId() + ".kit");

        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);

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

        boolean wasAlive = alive.remove(player.getUniqueId());
        lastHit.remove(player.getUniqueId());

        if (state == EventState.COUNTDOWN && participants.size() < minPlayers) {
            cancelCountdown();
            state = EventState.LOBBY;
            broadcastToParticipants(m("countdown-cancelled"));
            updateBar();
            return;
        }

        if (state == EventState.RUNNING && !gameOver && wasAlive) {
            broadcastToParticipants(m("player-left", "player", player.getName(), "alive", alive.size()));
            checkWinner();
        }
        updateBar();
    }

    /** /event give all: alleen wie nog leeft, niet de toeschouwers. */
    @Override
    public List<Player> giveTargets() {
        return alivePlayers();
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
                bar.color(BossBar.Color.RED);
                bar.progress(clamp((float) participants.size() / minPlayers));
            }
            case COUNTDOWN -> {
                bar.name(r("bossbar-lobby-countdown", "seconds", lobbyLeft));
                bar.color(BossBar.Color.GREEN);
                bar.progress(clamp((float) lobbyLeft / lobbyCountdown));
            }
            case RUNNING -> {
                if (phase == Phase.PREP) {
                    bar.name(r("bossbar-prep", "seconds", counter));
                    bar.color(BossBar.Color.GREEN);
                    bar.progress(clamp((float) counter / startDelay));
                } else {
                    String time = String.format("%d:%02d", elapsed / 60, elapsed % 60);
                    bar.name(r("bossbar-play", "alive", alive.size(), "time", time));
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
        glowing = false;
        elapsed = 0;
        alive.clear();
        kills.clear();
        lastHit.clear();

        List<Player> fighters = players();
        List<Location> spots = spreadSpots(fighters.size());

        for (int i = 0; i < fighters.size(); i++) {
            Player p = fighters.get(i);
            alive.add(p.getUniqueId());
            kills.put(p.getUniqueId(), 0);

            p.teleport(i < spots.size() ? spots.get(i) : spawn);
            p.setGameMode(GameMode.ADVENTURE);
            giveKit(p);
            prepareFighter(p);

            p.showTitle(Title.title(
                    r("title-start"),
                    r("subtitle-start"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        }
        startedWith = alive.size();

        // Korte voorbereiding: iedereen staat stil en er kan nog niet gevochten worden.
        phase = Phase.PREP;
        counter = startDelay;
        updateBar();
        gameTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Kit uit de config. Elke regel: "<item> [plek] [aantal]", zelfde schrijfwijze als /event give. */
    private void giveKit(Player p) {
        for (String line : kit) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) continue;

            String slot = parts.length >= 2 ? parts[1] : null;
            int amount = 1;
            if (parts.length >= 3) {
                try {
                    amount = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning(getDisplayName() + " kit: ongeldig aantal in '" + line + "'");
                }
            }
            Optional<ItemStack> item = ItemGiver.parseItem(parts[0], amount);
            if (item.isEmpty()) {
                plugin.getLogger().warning(getDisplayName() + " kit: onbekend item in '" + line + "' (gebruik: <item> [plek] [aantal])");
                continue;
            }
            if (!ItemGiver.isValidSlot(slot)) {
                plugin.getLogger().warning(getDisplayName() + " kit: onbekende plek in '" + line + "', item gaat naar de eerste vrije plek");
                slot = null;
            }
            ItemGiver.give(p, item.get(), slot);
        }
    }

    /**
     * Startplekken zo ver mogelijk uit elkaar: alle veilige plekken in de arena
     * (vaste grond met 2 blokken lucht erboven), daarna steeds de plek die het verst van de rest ligt.
     */
    private List<Location> spreadSpots(int count) {
        List<Location> candidates = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = maxY - 1; y >= minY; y--) {
                    Block floor = world.getBlockAt(x, y, z);
                    if (!floor.isSolid()) continue;
                    if (floor.getRelative(0, 1, 0).isPassable() && floor.getRelative(0, 2, 0).isPassable()) {
                        candidates.add(new Location(world, x + 0.5, y + 1, z + 0.5));
                    }
                    break; // alleen het bovenste blok per kolom
                }
            }
        }
        if (candidates.isEmpty()) return List.of();

        Collections.shuffle(candidates, random);
        if (candidates.size() > 3000) candidates = new ArrayList<>(candidates.subList(0, 3000));

        double cx = (minX + maxX + 1) / 2.0;
        double cz = (minZ + maxZ + 1) / 2.0;
        List<Location> chosen = new ArrayList<>();
        chosen.add(candidates.removeFirst());
        while (chosen.size() < count && !candidates.isEmpty()) {
            int best = 0;
            double bestDist = -1;
            for (int i = 0; i < candidates.size(); i++) {
                double d = Double.MAX_VALUE;
                for (Location c : chosen) d = Math.min(d, c.distanceSquared(candidates.get(i)));
                if (d > bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            chosen.add(candidates.remove(best));
        }

        // Iedereen kijkt naar het midden van de arena.
        for (Location l : chosen) {
            l.setDirection(new Vector(cx - l.getX(), 0, cz - l.getZ()));
        }
        // Meer spelers dan plekken: plekken worden hergebruikt.
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(chosen.get(i % chosen.size()).clone());
        return out;
    }

    // ------------------------------------------------------------------
    // Spelloop
    // ------------------------------------------------------------------

    /** 1x per seconde: fases, gloeien tegen verstoppen, bossbar en actionbar. */
    private void tick() {
        if (gameOver || state != EventState.RUNNING) return;

        if (phase == Phase.PREP) {
            updateBar();
            if (counter <= 0) {
                phase = Phase.PLAY;
                elapsed = 0;
                for (Player p : players()) {
                    p.showTitle(Title.title(
                            r("go-title"), r("go-subtitle"),
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
        if (glowAfter > 0 && !glowing && elapsed >= glowAfter) {
            glowing = true;
            for (Player p : alivePlayers()) p.setGlowing(true);
            broadcastToParticipants(m("glow"));
        }
        for (Player p : alivePlayers()) {
            p.sendActionBar(r("actionbar", "kills", kills.getOrDefault(p.getUniqueId(), 0)));
        }
        updateBar();
    }

    private void eliminate(Player p, UUID killerId) {
        UUID id = p.getUniqueId();
        if (!alive.remove(id)) return;
        lastHit.remove(id);

        Player killer = killerId == null ? null : Bukkit.getPlayer(killerId);
        if (killer != null && alive.contains(killerId)) {
            kills.merge(killerId, 1, Integer::sum);
            onKill(killer);
            killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            broadcastToParticipants(m("killed", "player", p.getName(), "killer", killer.getName(),
                    "alive", alive.size()));
        } else {
            broadcastToParticipants(m("died", "player", p.getName(), "alive", alive.size()));
        }

        p.setGlowing(false);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(spawn);
        p.showTitle(Title.title(
                r("eliminated-title"),
                r("eliminated-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        updateBar();
        checkWinner();
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

        Player winner = alive.isEmpty() ? null : Bukkit.getPlayer(alive.iterator().next());

        if (winner != null) {
            int k = kills.getOrDefault(winner.getUniqueId(), 0);
            announce(m("winner-broadcast", "player", winner.getName(), "kills", k));
            for (Player p : players()) {
                p.showTitle(Title.title(
                        r("winner-title", "player", winner.getName()),
                        r("winner-subtitle", "player", winner.getName(), "kills", k),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            celebrate(winner.getLocation());
            bar.name(r("bossbar-winner", "player", winner.getName()));
        } else {
            announce(m("no-winner"));
            bar.name(r("bossbar-nowinner"));
        }
        bar.color(BossBar.Color.PURPLE);
        bar.progress(1f);

        RewardService.give(plugin, getId(), startedWith, winner, players());

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
        if (endTask != null) { endTask.cancel(); endTask = null; }

        if (bar != null) {
            for (Player p : players()) p.hideBossBar(bar);
        }
        alive.clear();
        kills.clear();
        lastHit.clear();
        gameOver = true;
        HandlerList.unregisterAll(this);

        // Losse pijlen en items in de arena opruimen.
        if (world != null) {
            BoundingBox box = new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).expand(2);
            for (Entity ent : world.getNearbyEntities(box)) {
                if (ent instanceof AbstractArrow || ent instanceof Item) ent.remove();
            }
        }

        if (forced) announce(m("stopped"));
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    private boolean inEvent(Entity entity) {
        return entity instanceof Player p && isParticipant(p);
    }

    private boolean isAlive(Entity entity) {
        return entity instanceof Player p && alive.contains(p.getUniqueId());
    }

    private boolean fighting() {
        return state == EventState.RUNNING && phase == Phase.PLAY && !gameOver;
    }

    /** De speler achter een aanval: direct of via een pijl/trident/sneeuwbal. */
    protected static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    /**
     * Alle schade rond het event op één plek.
     * - Alleen levende deelnemers vechten met elkaar; niemand van buiten kan meedoen.
     * - PvP tussen levende deelnemers gaat altijd door, ook als een beschermplugin (bv. WorldGuard met
     *   pvp deny) de klap al blokkeerde; daarom ignoreCancelled = false. Andere schade (val, lava, ...)
     *   die een andere plugin al blokkeerde, blijft geblokkeerd.
     * - Dodelijke klap: niet echt doodgaan, maar uitgeschakeld worden (geen deathscreen, geen gedropte spullen).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent e) {
        Player attacker = e instanceof EntityDamageByEntityEvent byEntity ? attackerOf(byEntity.getDamager()) : null;
        boolean victimIn = inEvent(e.getEntity());
        boolean attackerIn = attacker != null && isParticipant(attacker);
        if (!victimIn && !attackerIn) return;

        if (!fighting() || !isAlive(e.getEntity()) || (attacker != null && !alive.contains(attacker.getUniqueId()))) {
            e.setCancelled(true);
            return;
        }
        Player p = (Player) e.getEntity();
        if (attacker != null) {
            e.setCancelled(false);
            if (attacker != p) lastHit.put(p.getUniqueId(), new Hit(attacker.getUniqueId(), System.currentTimeMillis()));
        } else if (e.isCancelled()) {
            return;
        }

        boolean isVoid = e.getCause() == EntityDamageEvent.DamageCause.VOID;
        if (!isVoid && !isLethal(p, e)) return;

        e.setCancelled(true);
        if (!isVoid && useTotem(p)) return;
        eliminate(p, killerOf(p, e));
    }

    /**
     * Totem of Undying in een hand: doet hetzelfde als in vanilla (eerst de hoofdhand, dan de andere hand).
     * Zelf nagebootst, omdat een klap bij One Shot ook dodelijk kan zijn als de echte schade dat niet is.
     */
    private boolean useTotem(Player p) {
        PlayerInventory inv = p.getInventory();
        EquipmentSlot hand;
        if (inv.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING) hand = EquipmentSlot.HAND;
        else if (inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) hand = EquipmentSlot.OFF_HAND;
        else return false;

        ItemStack totem = inv.getItem(hand);
        totem.setAmount(totem.getAmount() - 1);
        inv.setItem(hand, totem);

        for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
        p.setHealth(Math.min(1.0, p.getHealth()));
        p.setFireTicks(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        p.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
        return true;
    }

    private UUID killerOf(Player victim, EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = attackerOf(byEntity.getDamager());
            if (attacker != null && attacker != victim) return attacker.getUniqueId();
        }
        return recentAttacker(victim);
    }

    /** Bv. de lava in of de leegte in geslagen: wie je in de laatste 10 seconden raakte krijgt de kill. */
    private UUID recentAttacker(Player victim) {
        Hit hit = lastHit.get(victim.getUniqueId());
        return hit != null && System.currentTimeMillis() - hit.time() < 10_000 ? hit.attacker() : null;
    }

    /** Vangnet als iemand toch doodgaat (bv. /kill): niets droppen en als toeschouwer terugkomen. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!isParticipant(p)) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        if (alive.contains(p.getUniqueId())) {
            UUID killer = recentAttacker(p);
            alive.remove(p.getUniqueId());
            broadcastToParticipants(m("died", "player", p.getName(), "alive", alive.size()));
            if (killer != null && alive.contains(killer)) {
                kills.merge(killer, 1, Integer::sum);
                Player k = Bukkit.getPlayer(killer);
                if (k != null) onKill(k);
            }
            updateBar();
            checkWinner();
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!isParticipant(p) || alive.contains(p.getUniqueId())) return;
        e.setRespawnLocation(spawn);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (isParticipant(p)) p.setGameMode(GameMode.SPECTATOR);
        }, 1L);
    }

    /** Tijdens de voorbereiding stilstaan; in het spel binnen de arena blijven. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!alive.contains(p.getUniqueId()) || state != EventState.RUNNING || gameOver) return;
        Location from = e.getFrom();
        Location to = e.getTo();

        if (phase == Phase.PREP) {
            if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                e.setTo(back);
            }
            return;
        }

        if (to.getY() < minY - 1) {
            eliminate(p, recentAttacker(p));
            return;
        }
        boolean outside = to.getX() < minX || to.getX() >= maxX + 1 || to.getZ() < minZ || to.getZ() >= maxZ + 1;
        if (outside) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (!hunger && inEvent(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (naturalRegen || !inEvent(e.getEntity())) return;
        EntityRegainHealthEvent.RegainReason reason = e.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED || reason == EntityRegainHealthEvent.RegainReason.REGEN) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isParticipant(e.getPlayer())) e.setCancelled(true);
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
