# Event-patronen — EventCore

## Levenscyclus van een event

```
/event start <id>
  EventManager.start → factory.get() (nieuwe instantie) → validate() → open() → onOpen()
                                                                       state = LOBBY
/event join   → GameEvent.join → snapshot + prepare → onJoin()
  genoeg spelers → state = COUNTDOWN (countdownTask)
  countdown klaar → state = RUNNING (gameTask, eventueel stepTask)
  winnaar/tijd op → gameOver = true → rewards → endTask → stop(false)
/event stop, lobby-timeout, server-stop → stop(true)

GameEvent.stop → state = ENDING → onStop(forced) → snapshots terug → participants leeg
               → state = IDLE → EventManager.finish()
```

`GameEvent` regelt join/leave/snapshot/stop. Het event vult alleen `validate`, `onOpen`, `onJoin`, `onLeave`, `onStop` in (en optioneel `setupKeys`, `handleSetup`, `setupArgs`, `giveTargets`).

---

## Skelet voor een nieuw event

```java
package nl.eventcore.event.mijnevent;

public final class MijnEvent extends GameEvent implements Listener {

    public static final String ID = "mijnevent";

    private enum Phase { PREP, PLAY }

    // spelverloop
    private final Set<UUID> alive = new HashSet<>();
    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask endTask;
    private BossBar bar;
    private Phase phase = Phase.PREP;
    private int lobbyLeft;
    private int startedWith;
    private boolean gameOver;

    // config
    private Location lobby;
    private Location spawn;
    private int minPlayers;
    private int maxPlayers;
    private int lobbyCountdown;
    private int winDelaySeconds;

    public MijnEvent(EventCorePlugin plugin) {
        super(plugin, ID, "Mijn Event");   // GEEN side-effects hier
    }

    private Component m(String key, Object... kv) { return plugin.messages().prefixed(getId() + "." + key, kv); }
    private Component r(String key, Object... kv) { return plugin.messages().get(getId() + "." + key, kv); }

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
        minPlayers = Math.max(2, cfg.getInt(getId() + ".min-players", 2));
        maxPlayers = Math.max(minPlayers, cfg.getInt(getId() + ".max-players", 20));
        lobbyCountdown = Math.max(5, cfg.getInt(getId() + ".lobby-countdown", 30));
        winDelaySeconds = Math.max(3, cfg.getInt(getId() + ".win-delay-seconds", 8));

        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        state = EventState.LOBBY;
        announce(m("opened", "min", minPlayers));
    }

    @Override
    protected void onJoin(Player player) {
        if (participants.size() > maxPlayers) {
            player.sendMessage(m("full"));
            leave(player);
            return;
        }
        player.teleport(lobby);
        player.showBossBar(bar);
        if (state == EventState.LOBBY && participants.size() >= minPlayers) startCountdown();
    }

    @Override
    protected void onLeave(Player player) {
        player.hideBossBar(bar);
        boolean wasAlive = alive.remove(player.getUniqueId());
        if (state == EventState.COUNTDOWN && participants.size() < minPlayers) {
            cancelCountdown();
            state = EventState.LOBBY;
            broadcastToParticipants(m("countdown-cancelled"));
            return;
        }
        if (state == EventState.RUNNING && wasAlive) checkWinner();
    }

    @Override
    protected void onStop(boolean forced) {
        cancelCountdown();
        if (gameTask != null) { gameTask.cancel(); gameTask = null; }
        if (endTask != null) { endTask.cancel(); endTask = null; }
        if (bar != null) for (Player p : players()) p.hideBossBar(bar);
        alive.clear();
        gameOver = true;
        HandlerList.unregisterAll(this);
        if (forced) announce(m("stopped"));
    }

    // startCountdown / cancelCountdown / beginGame / tick / checkWinner: zie TntTagEvent
}
```

Registreer in `EventCorePlugin#onEnable`:

```java
eventManager.register("mijnevent", () -> new MijnEvent(this));
```

Voeg daarna toe: `mijnevent:`-sectie in `config.yml` (met commentaar over de setup-commando's), `mijnevent:`-sectie in `messages.yml` (minimaal `opened`, `full`, `lobby-not-set`, `spawn-not-set`, `countdown-cancelled`, `stopped` en de bossbar-/winnaarteksten) en een kopje in `README.md`.

---

## Winnaar & einde

```java
private void checkWinner() {
    if (gameOver || state != EventState.RUNNING) return;
    if (alive.size() > 1) return;

    gameOver = true;
    if (gameTask != null) { gameTask.cancel(); gameTask = null; }

    Player winner = alive.isEmpty() ? null : Bukkit.getPlayer(alive.iterator().next());
    if (winner != null) {
        announce(m("winner-broadcast", "player", winner.getName()));
        celebrate(winner.getLocation());
    } else {
        announce(m("no-winner"));
    }
    RewardService.give(plugin, getId(), startedWith, winner, players());
    endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> stop(false), winDelaySeconds * 20L);
}
```

- `startedWith` = aantal spelers bij de start; `RewardService` vergelijkt dit met `rewards.min-players` (tegen alt-farming).
- Races met plekken: houd een `List<UUID> finished` bij en gebruik `RewardService.givePlaces(...)`. Stop zodra `winners` plekken vergeven zijn, iedereen binnen is, of `max-duration-seconds` verstreken is.
- `checkWinner()` wordt ook vanuit `onLeave()` aangeroepen — dus een speler die quit kan het spel beëindigen. Laat het idempotent zijn (`if (gameOver) return`).

---

## Fases binnen RUNNING

Veel events hebben een PREP-fase (`start-delay-seconds`): iedereen staat op zijn plek, maar spelen mag nog niet. Patroon:

- `phase = Phase.PREP` bij `beginGame()`, in `tick()` aftellen, daarna `phase = Phase.PLAY`.
- Freeze via `PlayerMoveEvent` (`e.setTo(from met nieuwe yaw/pitch)`) — zie `LastManStandingEvent.onMove()`.
- Schade/breken/glijden blokkeren zolang `phase == PREP`.

---

## Varianten (zoals Hot Potato, One Shot)

Een variant extend het basis-event via de `protected`-constructor met eigen id en naam:

```java
public final class HotPotatoEvent extends TntTagEvent {
    public static final String ID = "hotpotato";

    public HotPotatoEvent(EventCorePlugin plugin) { super(plugin, ID, "Hot Potato"); }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        holders = Math.max(1, cfg.getInt(ID + ".holders", 1));
        super.onOpen();          // basis leest de rest onder getId() = "hotpotato"
    }

    @Override protected int fuseLength() { ... }   // haakje overschrijven
}
```

Voorwaarden voor het basis-event:
- Klasse niet `final`, een `protected (plugin, id, displayName)`-constructor.
- Alle config/messages via `getId()`, nooit een hardcoded id.
- Uitbreidingspunten als `protected`-methodes met een standaardgedrag (`taggerCount`, `fuseLength`, `prepareFighter`, `isLethal`, `onKill`).
- De variant moet zijn **eigen** volledige config- en messages-sectie hebben (setup, rewards, alle teksten) — er is geen fallback naar de sectie van het basis-event.

---

## Spelerstaat aanpassen

`PlayerSnapshot.prepare()` maakt de speler al schoon: inventaris leeg, ADVENTURE, geen vlucht, volle health/honger, geen effecten, geen glow, `NoCollision`. Wat een event daarbovenop doet:

| Aanpassing | Wordt teruggezet door |
|---|---|
| Inventaris, gamemode, health, honger, xp, effecten, glow, vlucht, collision/team, locatie | `PlayerSnapshot.restore()` |
| `MAX_HEALTH`-basiswaarde (One Shot) | `PlayerSnapshot` (`maxHealthBase`) |
| Bossbar | Het event (`hideBossBar` in `onLeave` + `onStop`) |
| Velocity, walk/fly speed, andere attributen, scoreboard, metadata | **Niets** — voeg toe aan `PlayerSnapshot` of zet zelf terug |

Bij twijfel: breid `PlayerSnapshot` uit. Dat geldt dan meteen voor alle events.

---

## Checklist

- [ ] Constructor zonder side-effects
- [ ] `validate()` checkt elke setup-locatie die `onOpen()` gebruikt
- [ ] Alle config onder `getId()`, met default + clamp
- [ ] Alle tasks als field, gecanceld in `onStop()`
- [ ] Bossbar verborgen in `onLeave()` en `onStop()`
- [ ] `checkWinner()`/einde idempotent via `gameOver`
- [ ] Geregistreerd in `EventCorePlugin`, secties in `config.yml` + `messages.yml`, uitleg in `README.md`
