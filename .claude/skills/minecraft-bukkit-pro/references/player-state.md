# Spelerdata — EventCore

Er zijn twee soorten spelerstaat in EventCore. Haal ze niet door elkaar.

| Soort | Waar | Levensduur |
|---|---|---|
| **Event-staat** (alive, checkpoint, tagger, fails, cooldowns) | Velden in de `GameEvent`-subklasse, gekeyed op `UUID` | Eén event — weg na `onStop()` |
| **Snapshot** (inventaris, health, gamemode, …) | `PlayerSnapshot` in `GameEvent.snapshots` | Van join tot leave/stop |
| **Blijvende data** (stats, records, voorkeuren) | Cache in een `*Manager` + database (`database-async.md`) | Over events en restarts heen |

---

## Event-staat

- `Set<UUID>`/`Map<UUID, …>` als instance-veld van het event. Gewone `HashMap`/`HashSet` volstaat: alles draait op main.
- Opruimen per speler in `onLeave()` (`remove(uuid)`), alles in `onStop()` (`clear()`).
- Nooit `Player` in een veld; nooit `static`.

---

## Blijvende data — cache-patroon

Pas toe zodra er stats/records/voorkeuren bijkomen.

```java
import lombok.Getter;

public final class PlayerStats {

    @Getter
    private final UUID uuid;

    private final Map<String, Integer> wins = new HashMap<>();   // per event-id

    public PlayerStats(UUID uuid) { this.uuid = uuid; }

    public int wins(String eventId) { return wins.getOrDefault(eventId, 0); }
    public void addWin(String eventId) { wins.merge(eventId, 1, Integer::sum); }
    public Map<String, Integer> allWins() { return Map.copyOf(wins); }   // nooit de interne map teruggeven
}
```

```java
public final class StatsManager implements Listener {

    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { load(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { cache.remove(e.getPlayer().getUniqueId()); }

    private void load(UUID uuid) {
        if (!loading.add(uuid)) return;                       // al bezig
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerStats stats = repository.load(uuid);        // JDBC, vangt SQLException zelf
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                loading.remove(uuid);
                if (Bukkit.getPlayer(uuid) != null) cache.put(uuid, stats);   // niet terugzetten na snelle quit
            });
        });
    }

    @Nullable
    public PlayerStats get(UUID uuid) { return cache.get(uuid); }
}
```

- **Reconnect-veiligheid**: de `loading`-set + online-check voorkomt dat een trage load na een quit een oud object in de cache zet.
- **Mutaties via de manager**, en wegschrijven via een increment-query (zie `database-async.md`), niet door de hele cache periodiek te dumpen. Heb je toch veel kleine wijzigingen: dirty-set + één periodieke async batch-save.
- Registreer de manager in `EventCorePlugin.onEnable()` alleen als `db.isEnabled()`; alle aanroepers checken `get(uuid) != null`.

---

## UUID is de sleutel, naam is weergave

Sla de naam op als laatst bekende weergavenaam naast de UUID (voor leaderboards zonder Mojang-lookup), maar zoek en join altijd op UUID.

---

## Corrupte data — skip en log per regel

Bij laden van een lijst (DB-rijen, locaties uit config): één kapotte regel overslaan met een `warning`, niet de hele load laten falen. Zoals `EventScheduler.load()` doet met ongeldige tijden en dagen.

---

## Checklist

- [ ] Event-staat in het event, blijvende staat in een manager — niet gemengd
- [ ] `UUID` als sleutel, nooit `Player`
- [ ] Interne collections niet direct teruggeven
- [ ] Async load met reconnect-guard
- [ ] Werkt als de database uit staat (`get()` geeft `null`, aanroepers checken)
