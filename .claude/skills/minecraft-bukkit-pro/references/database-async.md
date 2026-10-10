# Database & async — EventCore

> EventCore heeft nog geen database. Voeg hem toe volgens `libraries-setup.md` (HikariCP + MariaDB, `database.enabled` in config). Typische toepassingen: wins/deelnames per event, beste tijden (parkour, dropper, elytra race), leaderboards.

## Fundamentele regel

**Alle JDBC op een async thread. Alle Bukkit-API op de main thread.**

`runTaskAsynchronously` voor DB-werk, `runTask` om terug te keren naar main. Geen `CompletableFuture.supplyAsync` en geen eigen `ExecutorService` — één patroon in de hele codebase.

**Een event wacht nooit op de database.** De spelloop, winnaarsbepaling en restore lopen volledig op in-memory state. De database krijgt achteraf het resultaat (fire-and-forget), en gelezen data (stats, records) wordt vooraf geladen.

---

## DatabaseManager

```java
public final class DatabaseManager {

    private final EventCorePlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(EventCorePlugin plugin) { this.plugin = plugin; }

    /** Opent de pool. Geen queries hier — tabellen maken gebeurt async in de repositories. */
    public boolean connect() {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("database.enabled", false)) return false;

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mariadb://" + cfg.getString("database.host", "localhost") + ":"
                + cfg.getInt("database.port", 3306) + "/" + cfg.getString("database.name", "eventcore"));
        hc.setUsername(cfg.getString("database.user", "eventcore"));
        hc.setPassword(cfg.getString("database.password", ""));
        hc.setMaximumPoolSize(Math.max(2, cfg.getInt("database.pool-size", 5)));
        hc.setPoolName("EventCore");
        try {
            dataSource = new HikariDataSource(hc);
            plugin.getLogger().info("Database verbonden.");
            return true;
        } catch (RuntimeException e) {   // Hikari gooit PoolInitializationException (runtime) bij een foute verbinding
            plugin.getLogger().log(Level.SEVERE, "Kon niet verbinden met de database, statistieken staan uit.", e);
            dataSource = null;
            return false;
        }
    }

    public boolean isEnabled() { return dataSource != null; }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) throw new SQLException("Database staat uit");
        return dataSource.getConnection();
    }

    public void disconnect() {
        if (dataSource != null) dataSource.close();
        dataSource = null;
    }
}
```

- Hikari's eerste verbinding blokkeert even; dat is bij `onEnable` acceptabel (eenmalig), maar **nooit** later op main opnieuw verbinden of retryen.
- `disconnect()` als **laatste** stap in `EventCorePlugin.onDisable()`, ná `stopActive()` (dat event-resultaten nog kan wegschrijven).
- Wachtwoord nooit loggen.

---

## Tabel aanmaken — altijd async

```java
public void initialize() {
    if (!db.isEnabled()) return;
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ec_stats (
                    uuid      VARCHAR(36) NOT NULL,
                    event_id  VARCHAR(32) NOT NULL,
                    name      VARCHAR(16) NOT NULL,
                    wins      INT         NOT NULL DEFAULT 0,
                    played    INT         NOT NULL DEFAULT 0,
                    best_ms   BIGINT      NULL,
                    PRIMARY KEY (uuid, event_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
            // Migraties: idempotent, bij elke start veilig
            s.execute("ALTER TABLE ec_stats ADD COLUMN IF NOT EXISTS best_ms BIGINT NULL");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Kon tabel ec_stats niet aanmaken", e);
        }
    });
}
```

- MariaDB ondersteunt `ADD COLUMN IF NOT EXISTS` — een nieuwe kolom altijd als migratie toevoegen, niet alleen in `CREATE TABLE IF NOT EXISTS` (dat doet niets op een bestaande tabel).
- Tabelprefix uit config mag, maar **alleen** na validatie tegen `[a-z0-9_]{0,16}` — een identifier kun je niet als `?` meegeven (zie SQL-injectie hieronder).

---

## Resultaat van een event wegschrijven (fire-and-forget)

Verzamel de data **op main** (namen, UUID's, plek, tijd) in een `record`, en geef alleen dat record mee aan de async task — nooit `Player`-objecten.

```java
public record EventResult(String eventId, List<Entry> entries) {
    public record Entry(UUID uuid, String name, boolean won, Long timeMs) {}
}

public void save(EventResult result) {
    if (!db.isEnabled() || result.entries().isEmpty()) return;
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
        String sql = """
            INSERT INTO ec_stats (uuid, event_id, name, wins, played, best_ms) VALUES (?, ?, ?, ?, 1, ?)
            ON DUPLICATE KEY UPDATE
                name    = VALUES(name),
                wins    = wins + VALUES(wins),
                played  = played + 1,
                best_ms = IF(VALUES(best_ms) IS NULL, best_ms,
                             IF(best_ms IS NULL OR VALUES(best_ms) < best_ms, VALUES(best_ms), best_ms))
        """;
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (EventResult.Entry e : result.entries()) {
                s.setString(1, e.uuid().toString());
                s.setString(2, result.eventId());
                s.setString(3, e.name());
                s.setInt(4, e.won() ? 1 : 0);
                if (e.timeMs() == null) s.setNull(5, Types.BIGINT); else s.setLong(5, e.timeMs());
                s.addBatch();
            }
            s.executeBatch();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Kon resultaat van " + result.eventId() + " niet opslaan", ex);
        }
    });
}
```

- **Increment in SQL** (`wins = wins + ?`), nooit eerst SELECT → optellen in Java → UPDATE.
- **Batch** (`addBatch`/`executeBatch`) op één connectie voor alle deelnemers — niet een async task per speler.
- Aanroepen vanuit het event op hetzelfde moment als `RewardService` (in `checkWinner()`/race-einde), niet in `onStop()` — een geforceerde stop is geen geldig resultaat.

---

## Laden met callback naar main

```java
public void loadTop(String eventId, int limit, Consumer<List<TopEntry>> callback) {
    if (!db.isEnabled()) { callback.accept(List.of()); return; }
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
        List<TopEntry> out = new ArrayList<>();
        String sql = "SELECT name, wins FROM ec_stats WHERE event_id = ? ORDER BY wins DESC LIMIT ?";
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, eventId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new TopEntry(rs.getString("name"), rs.getInt("wins")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Kon top van " + eventId + " niet laden", e);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(out));
    });
}
```

- De callback draait op main en mag dus Bukkit-API gebruiken — maar de speler kan intussen offline zijn: `Player p = Bukkit.getPlayer(uuid); if (p == null) return;`.
- Sorteer/filter je op een kolom buiten de primary key (`wins`, `best_ms`)? Voeg een index toe: `CREATE INDEX idx_ec_stats_event_wins ON ec_stats (event_id, wins)` — check vooraf via `INFORMATION_SCHEMA.STATISTICS`, want `CREATE INDEX IF NOT EXISTS` is niet overal betrouwbaar.

---

## Geen query in een hot path

Nooit een DB-call in `PlayerMoveEvent`, een `stepTask`/`gameTask` of een GUI-click-handler. Laad wat je nodig hebt vooraf (bv. persoonlijk record bij join in de lobby → in een `Map<UUID, Long>` van het event), en schrijf achteraf één keer weg.

---

## SQL-injectie — ook via identifiers

`?`-placeholders beschermen waarden, geen tabel- of kolomnamen. Een dynamische identifier (tabelprefix uit config, sorteerkolom) komt alleen uit een gesloten enum/allowlist of wordt gevalideerd tegen een strikte regex. `event_id` is een **waarde** → gewoon `?`.

---

## Transacties

Meerdere statements die samen moeten slagen (bv. een seizoen resetten: archiveren + leegmaken): `conn.setAutoCommit(false)` → statements → `commit()`, `rollback()` in de catch, `setAutoCommit(true)` in `finally`.

---

## Database offline

Elke DB-methode begint met `if (!db.isEnabled()) return;` (of een lege callback) en vangt `SQLException` + logt met stacktrace. Een offline database mag een stats-feature laten degraderen, maar **nooit** een event laten falen, de main thread laten hangen of de plugin laten crashen.

---

## Shutdown

Bij `onDisable()` mag blocking I/O op main. Volgorde:
1. `eventScheduler.stop()`
2. `eventManager.stopActive()` (restore spelers)
3. Openstaande writes synchroon afmaken als je een queue/dirty-set hebt (direct JDBC, geen scheduler — die draait niet meer)
4. `databaseManager.disconnect()`

---

## Checklist

- [ ] Try-with-resources op `Connection`, `PreparedStatement`, `ResultSet`
- [ ] Logging met `plugin.getLogger().log(Level.SEVERE, msg, e)` — niet alleen `e.getMessage()`
- [ ] Tabel aanmaken via `runTaskAsynchronously`, migraties idempotent
- [ ] Callback naar main via `runTask`, met null-check op de speler
- [ ] Geen `Player`-objecten in async code — alleen UUID/naam/records
- [ ] Increments in SQL, batch voor meerdere rijen
- [ ] Werkt met `database.enabled: false` en met een offline database
- [ ] Geen query in move-handlers, ticks of GUI-clicks
- [ ] `disconnect()` als laatste in `onDisable()`
