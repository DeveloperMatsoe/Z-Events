---
name: minecraft-bukkit-pro
description: >
  MUST USE for ANY task touching Java code in this EventCore plugin, plugin.yml, config.yml,
  messages.yml, build.gradle.kts, or Bukkit/Paper API — including new events (minigames),
  bug fixes, refactors, reviews and performance issues. If the user mentions an event
  (tnttag, spleef, lms, parkour, ...), /event command, setup option, listener, task, arena,
  bossbar, reward or schedule in this project: load this skill first, always. Do NOT skip
  this skill for "quick edits" — the lifecycle and restore rules alone prevent players
  losing their inventory.
metadata:
  source: tailored (afgeleid van de SkyblockCore-skill)
  license: MIT
---

# EventCore — Agent Workflow

Stack: **Paper 26.2** · **Java 25** · **Gradle** · **Adventure/MiniMessage** · en waar nodig **Lombok**, **HikariCP + MariaDB** en **triumph-gui** (dezelfde versies als SkyblockCore).

> De libraries zitten nog niet in `build.gradle.kts`. Heeft een taak er één nodig, voeg hem dan toe volgens `references/libraries-setup.md` (shadow + relocatie onder `nl.eventcore.libs.*`). Voeg niets toe wat de taak niet gebruikt.

EventCore draait **maximaal één event tegelijk**. Een event is een `GameEvent`-subklasse die bij `/event start <id>` vers wordt aangemaakt, zichzelf als listener registreert, spelers opslaat via `PlayerSnapshot` en alles weer terugzet in `stop()`.

---

## Stap 1 — Begrijp de taak (ALTIJD eerst)

| Taaktype | Signaalwoorden | Wat je doet in stap 2 |
|---|---|---|
| **Nieuw event** | "nieuw event", "minigame", "voeg X toe" | Lees `GameEvent` + het meest gelijkende bestaande event (zie tabel hieronder) |
| **Variant van een event** | "zoals TNT Tag maar…", "zoals LMS maar…" | Lees `HotPotatoEvent`/`OneShotEvent`: extend het bestaande event en overschrijf de haakjes |
| **Bug fix** | "crash", "NPE", "niet werkt", "inventaris kwijt", "TPS" | Lees de failing klasse + Snelle diagnose |
| **Setup/commando** | "setup", "command", "/event …" | Lees `EventCommand` + `handleSetup`/`setupKeys` van het event |
| **Review** | "controleer", "klopt dit", "bekijk" | Lees het volledige bestand, check alle harde regels |
| **Tekst/config** | "bericht", "config-optie" | Lees `messages.yml`/`config.yml` + waar de key gelezen wordt |
| **Stats/database** | "stats", "leaderboard", "record", "opslaan", "database" | Lees `references/database-async.md` + `player-state.md`; staat er nog geen DB in, eerst `libraries-setup.md` |
| **GUI/menu** | "menu", "gui", "inventory-scherm" | Lees `references/gui-patterns.md`; nog geen triumph-gui, eerst `libraries-setup.md` |

---

## Stap 2 — Lees context (altijd minimaal 2 bestanden)

**Lees altijd:**
1. Het doelbestand of het meest vergelijkbare event
2. `event/GameEvent.java` als je in een event werkt — de levenscyclus staat daar, niet in het event zelf

**Lees ook als van toepassing:**
- `event/EventManager.java` — bij start/stop, lobby-timeout of "er draait al een event"
- `event/EventCommand.java` + `plugin.yml` — bij commands of permissies
- `util/PlayerSnapshot.java` — bij alles wat de spelerstaat verandert (max health, gamemode, effecten, flight, team)
- `src/main/resources/config.yml` + `messages.yml` — bij elke nieuwe optie of tekst
- `README.md` — bevat de handleiding voor admins; werk die bij als je setup of gedrag verandert

**Nooit** gokken over methode-signaturen of message-keys. Lees de klasse of `messages.yml`.

---

## Stap 3 — Plan (voor niet-triviale taken)

Korte interne bullet-lijst vóór je code schrijft:
- Welke klassen/config-keys/message-keys komen erbij of veranderen?
- Welke fases heeft het event (LOBBY → COUNTDOWN → RUNNING [PREP/PLAY] → ENDING) en welke tasks horen bij welke fase?
- Wat verandert er aan de speler dat `PlayerSnapshot` niet terugzet?
- Welke wereldwijzigingen moeten na afloop hersteld worden?

Voor < 10 regels code: sla planning over.

---

## Stap 4 — Schrijf code (volg deze volgorde)

1. **`onStop()` eerst** — zet het opruimen neer zodra je een task, bossbar of listener toevoegt.
2. **Config lezen in `onOpen()`** — met defaults en clamps, niet in de constructor.
3. **State declareren** — `Set<UUID>`/`Map<UUID, …>` voor spelers, nooit `Player`.
4. **Implementeer** — volg de patronen uit `references/event-patterns.md`.
5. **Config + messages + README** — elke nieuwe key in `config.yml` (met commentaar) en `messages.yml`, en de admin-uitleg in `README.md`.
6. **Registreren** — `eventManager.register("id", () -> new MijnEvent(this))` in `EventCorePlugin#onEnable`.

---

## Stap 5 — Zelfverificatie (na elke implementatie)

- [ ] **Lifecycle**: Wordt elke `BukkitTask` als field opgeslagen en in `onStop()` gecanceld + op `null` gezet?
- [ ] **Lifecycle**: Doet `onStop()` `HandlerList.unregisterAll(this)` als het event zichzelf registreerde?
- [ ] **Lifecycle**: Wordt de bossbar in `onLeave()` én `onStop()` verborgen?
- [ ] **Constructor**: Doet de constructor niets behalve velden zetten? (zie harde regel 2)
- [ ] **Restore**: Wordt alles wat het event aan de speler verandert door `PlayerSnapshot` teruggezet, of door het event zelf in `onLeave`/`onStop`?
- [ ] **Restore**: Wordt elke wereldwijziging hersteld (`FloorArena.restore()`/`remember()`)?
- [ ] **State-checks**: Checkt elke handler en elke tick `isParticipant`/`state`/`gameOver` vóór hij iets doet?
- [ ] **UUID's**: Geen `Player`-objecten in velden of collections.
- [ ] **Teksten**: Alle speler-zichtbare tekst via `messages.yml` (`m(...)`/`r(...)`/`plugin.messages()`), geen hardcoded strings, geen `§`-codes.
- [ ] **Config**: Elke waarde heeft een default en een `Math.max`/`Math.min`-clamp.
- [ ] **Commands**: `args.length` gecheckt vóór `args[n]`; permissie gecheckt; tab-complete bijgewerkt.
- [ ] **Null-checks**: `Bukkit.getPlayer(uuid)`, `cfg.getLocation(...)`, `getAttribute(...)`, `getItemMeta()` gecheckt op `null`.
- [ ] **Logging**: Geen `e.printStackTrace()`/`System.out` — `plugin.getLogger()`.
- [ ] **Lombok**: Staat `import lombok.Getter;` erbij? Staat `@Getter` op een eigen regel, los van `private`/`public`?
- [ ] **Encapsulatie**: Geeft een getter een interne `Map`/`Set`/`List` direct terug? Geef een kopie terug.
- [ ] **Database**: JDBC alleen async, geen `Player` in async code, werkt met `database.enabled: false`?
- [ ] **GUI**: `disableAllInteractions()` aanwezig? Laat de event-listener clicks in de GUI door?
- [ ] **Libraries**: Nieuwe runtime-library gereloceerd onder `nl.eventcore.libs.*`?

Faalt een check: herstel het **vóór** je de code presenteert.

---

## Harde regels (nooit breken)

1. **Een speler komt altijd terug zoals hij binnenkwam.** `GameEvent.join()` maakt een `PlayerSnapshot` en `leave()`/`stop()` zetten die terug. Verander je iets aan de speler dat de snapshot niet kent (een nieuw attribuut, een scoreboard, een metadata-tag, een persistente potion), breid dan `PlayerSnapshot` uit **of** zet het zelf terug in `onLeave()` én `onStop()`. Inventarisverlies is de ergste bug die deze plugin kan hebben.
2. **De constructor van een event heeft geen side-effects.** `EventManager` roept de factory (`() -> new MijnEvent(this)`) ook aan voor `displayName()`, `setupKeys()`, `setupArgs()` en `handleSetup()` — dus bij elke tab-complete en elk setup-commando wordt er een wegwerp-instantie gemaakt. Listeners registreren, tasks starten, bossbars maken en config lezen hoort in `onOpen()`, nooit in de constructor.
3. **Alles wat in `onOpen()`/tijdens het spel gestart wordt, stopt in `onStop()`.** Elke `BukkitTask` is een field, wordt gecanceld en op `null` gezet; `HandlerList.unregisterAll(this)`; bossbar verbergen voor `players()`. `onStop()` moet ook veilig zijn als het event nog in LOBBY stond (tasks kunnen `null` zijn).
4. **`stop()` alleen via de basisklasse.** Roep `stop(false)` aan aan het einde van het spel (meestal via `endTask` na `win-delay-seconds`) en laat `EventManager.stop()` `stop(true)` doen. Zet `state` nooit zelf op `IDLE`/`ENDING` — `GameEvent.stop()` beheert dat en meldt het event af bij de manager.
5. **UUID's opslaan, nooit `Player`-referenties.** Gebruik `players()` of `Bukkit.getPlayer(uuid)` + null-check op het moment dat je de speler nodig hebt.
6. **Elke handler checkt eerst of het hem aangaat.** `if (!isParticipant(p)) return;` (of `inEvent(entity)`) als eerste regel — listeners vangen events van de hele server, niet alleen van deelnemers. Checks op `state == RUNNING` en `!gameOver` waar de actie alleen tijdens het spel mag.
7. **Alle Bukkit-API op de main thread, geen blocking I/O op main.** JDBC/HTTP alleen in `runTaskAsynchronously`; terug naar main via `runTask`. In async code alleen UUID's, namen en records — nooit `Player`, `Location` of `World`. Een event wacht nooit op de database: spelloop en winnaar lopen in-memory, het resultaat wordt achteraf weggeschreven.
8. **Teksten in `messages.yml`, waarden in `config.yml`.** Geen hardcoded speler-tekst; per event onder `<id>.<key>`. Gameplay-getallen (tijden, aantallen, radii) zijn config-opties met default + clamp. Technische constanten worden `private static final`.
9. **Config-keys onder het event-id, niet onder een vaste string.** Gebruik `getId() + ".optie"` (niet `"tnttag.optie"`) zodat een subklasse-variant zoals `HotPotatoEvent` automatisch zijn eigen sectie gebruikt.
10. **Geen nieuwe static mutable state.** Caches, tellers en spelerssets zijn instance-velden van het event of de manager. Een event-instantie leeft maar één event lang — dat is precies wat je wilt.
11. **Geen lege catch-blocks, geen `catch (Exception e)`.** Vang de specifieke exceptie (`NumberFormatException`, `IllegalArgumentException`, `DateTimeParseException`) en geef een bericht of log op `warning`. Een `catch (NumberFormatException ignored)` bij het parsen van een command-argument mag alleen als er daarna een usage-bericht volgt.
12. **Geen deprecated Bukkit/Paper-API zonder reden.** Gebruik de Adventure-API (`sendMessage(Component)`, `showTitle`, `BossBar.bossBar`, `displayName(Component)`), niet de oude `String`-varianten. Een deprecated methode alleen met comment waarom.
13. **Herstel de wereld.** Elk blok dat een event verandert, staat vooraf in een snapshot (`FloorArena.takeSnapshot()` of `remember(block)`) en wordt in `onStop()` teruggezet — ook bij een geforceerde stop of server-shutdown (`onDisable` → `stopActive()`).
14. **Lombok-import verplicht, `@Getter` op een eigen regel.** Zonder `import lombok.Getter;` ziet de compiler `@Getter` niet. Geen `@Data` op klassen met collections; geen Lombok-getter op een interne collection.
15. **Getters geven nooit een interne mutable collection terug.** `Map.copyOf(...)`/`List.copyOf(...)`/`Collections.unmodifiable…`.
16. **De database is optioneel.** `database.enabled: false` is de default en alles behalve stats blijft werken. Een offline database laat een feature degraderen (gelogde fout, leeg resultaat), maar laat nooit een event falen of de main thread hangen.
17. **Elke GUI heeft `disableAllInteractions()`.** En identificeer een GUI nooit op titel.

---

## Event priority & cancellation

Andere plugins (WorldGuard, region-protection, PvP-toggles) zitten op dezelfde events. EventCore moet in de arena winnen, maar nergens anders iets kapotmaken.

| Situatie | Patroon in dit project |
|---|---|
| Iets **verbieden** voor deelnemers (drop, swap, inventory-click, place, honger) | `@EventHandler(ignoreCancelled = true)` + `if (isParticipant(p)) e.setCancelled(true);` |
| Iets **forceren** dat een andere plugin misschien blokkeert (tikken in TNT Tag, blokken breken in Spleef) | `priority = EventPriority.HIGHEST, ignoreCancelled = false`, en expliciet `setCancelled(false)` alleen als de actie echt binnen de arena/regels valt |
| Schade volledig uit (TNT Tag, Parkour, Elytra Race) | `HIGHEST`, `ignoreCancelled = false`, altijd `setCancelled(true)` voor deelnemers |
| Alleen observeren | `MONITOR` — **nooit** cancellen op `MONITOR` |

- Het un-cancellen (`setCancelled(false)`) is een bewuste override van andere plugins: doe het **alleen** na een strikte check (deelnemer, `RUNNING`, blok binnen `FloorArena`, toegestaan materiaal). Zie `SpleefEvent.onBreak()`.
- Cancel als eerste statement, vóór side-effects.
- Een `PlayerMoveEvent`-freeze (PREP-fase) zet `e.setTo(...)` met behoud van yaw/pitch — zie `LastManStandingEvent.onMove()`.

---

## Logging

- `plugin.getLogger().info/warning/severe(...)`. Bij een echte exceptie: `plugin.getLogger().log(Level.SEVERE, "…", e)` zodat de stacktrace erbij staat.
- `warning` voor foute config-waarden (onbekend blok, ongeldige tijd, onbekend event in het schema) — en ga verder met een fallback, zoals `EventScheduler.load()` en `FloorArena.parseMaterials()` doen.
- Nooit loggen in een handler of task die vaak vuurt (`PlayerMoveEvent`, `stepTask` per 1-2 ticks).

---

## Bestaande events — kies je template

| Event | Klasse | Bruikbaar als template voor |
|---|---|---|
| TNT Tag | `tnttag/TntTagEvent` | Rondes + tikken + eliminatie; heeft haakjes voor varianten (`taggerCount`, `fuseLength`, `showFuseTime`, `tagItem`) |
| Hot Potato | `hotpotato/HotPotatoEvent` | **Variant-patroon**: extend + overschrijf haakjes, eigen config/teksten via eigen id |
| Spleef | `spleef/SpleefEvent` | Vloer die kapot gaat door spelers, `FloorArena`, decay-timer, blok-break override |
| TNT Run | `tntrun/TntRunEvent` | Vloer die verdwijnt onder je voeten, `stepTask` elke 2 ticks, double-jump |
| Last Man Standing | `lms/LastManStandingEvent` | PvP-arena met pos1/pos2, kit uit config, spreiden, PREP-freeze, death/respawn-afhandeling |
| One Shot | `oneshot/OneShotEvent` | Variant van LMS die max health aanpast (`PlayerSnapshot` zet `maxHealthBase` terug) |
| Elytra Race | `elytrarace/ElytraRaceEvent` | Race met geordende checkpoints, plekken/top 3, `checkpoint add/insert/remove/tp/show` setup |
| Parkour | `parkour/ParkourEvent` | Race met checkpoints-als-gebied, val-detectie, reset-item |
| Dropper | `dropper/DropperEvent` | Meerdere maps, portals/finish-gebieden, skip-item na X fails |

Gedeelde stukken: `RewardService` (beloningen via console-commando's), `FloorArena` (vloer-snapshot + restore), `ItemGiver` (items/kit uit `/give`-syntax), `NoCollision` (botsen uit), `PlayerSnapshot` (staat opslaan/terugzetten), `Messages` (MiniMessage).

---

## API-keuzetabel

| Doel | Gebruik | Nooit |
|---|---|---|
| Tekst naar speler | `m("key", "naam", waarde, …)` (met prefix) / `r("key", …)` (titel, actionbar, bossbar, itemnaam) / `plugin.messages().prefixed(...)` | Hardcoded strings, `§`-codes, `ChatColor` |
| Server-brede melding | `announce(Component)` | `Bukkit.broadcastMessage(String)` |
| Placeholders | `Placeholder.unparsed` via `Messages` (naam/waarde-paren) | String-concatenatie in MiniMessage (injectie van `<tags>` via spelernamen) |
| Spelerstaat opslaan/herstellen | `GameEvent.join/leave/stop` + `PlayerSnapshot` | Zelf inventaris kopiëren in een event |
| Items/kit | `ItemGiver.parseItem(...)` + `ItemGiver.give(p, item, slot)` | Eigen parser voor item-strings |
| Beloningen | `RewardService.give(...)` / `givePlaces(...)` | Zelf `dispatchCommand` in een event |
| Vloer/regio herstellen | `FloorArena` (`takeSnapshot`, `remember`, `restore`) | Losse `List<Block>` zonder BlockData |
| Bossbar | Adventure `BossBar.bossBar(...)`, `player.showBossBar/hideBossBar` | Bukkit `createBossBar` |
| Locaties uit setup | `cfg.getLocation(getId() + ".lobby")` + null-check in `validate()` | Locaties als losse x/y/z-strings |
| Periodieke taak | `runTaskTimer` + `BukkitTask`-field, gecanceld in `onStop()` | Naakte `Thread`, `ScheduledExecutorService` |
| Deeltjes alleen voor één speler | `new ParticleBuilder(...).receivers(viewer)` | `world.spawnParticle` voor iedereen bij setup-previews |
| Database | `DatabaseManager` (HikariCP) + repository met `runTaskAsynchronously`, zie `references/database-async.md` | JDBC op main, `CompletableFuture`, eigen threadpool |
| Menu | triumph-gui `Gui.gui()...disableAllInteractions()` met `Component`-titels uit `messages.yml` | Eigen `InventoryClickEvent`-menu's op titel herkennen |
| Data-klassen | `record` (immutable) of klasse met Lombok `@Getter` | `@Data` op mutable state |

---

## Permissions

Er zijn er twee, en nieuwe features passen daar meestal in:

| Permissie | Default | Voor |
|---|---|---|
| `eventcore.play` | `true` | join, leave, list, next |
| `eventcore.admin` | `op` | start, stop, setup, give, reload |

Een nieuwe permissie alleen als het echt nodig is, dan onder `eventcore.<actie>`, geregistreerd in `plugin.yml` met een `default`.

---

## Config & messages

**Config-backed waarden** — gelezen in `onOpen()`, met default én clamp:

```java
FileConfiguration cfg = plugin.getConfig();
minPlayers     = Math.max(2, cfg.getInt(getId() + ".min-players", 4));
maxPlayers     = Math.max(minPlayers, cfg.getInt(getId() + ".max-players", 30));
lobbyCountdown = Math.max(5, cfg.getInt(getId() + ".lobby-countdown", 30));
winDelaySeconds = Math.max(3, cfg.getInt(getId() + ".win-delay-seconds", 8));
```

Een 0 of negatieve waarde voor een timer, radius of aantal kan een oneindige loop, een instant-win of een deling door nul geven — vertrouw de config nooit blind.

**Nieuwe config-opties** komen in `src/main/resources/config.yml` met een Nederlands commentaar. `EventCorePlugin.onEnable()` doet `copyDefaults(true)` + `saveConfig()`, dus bestaande servers krijgen de nieuwe opties automatisch — maar alleen als ze in het resource-bestand staan.

**Materiaallijsten** lees je met `FloorArena.readNames(...)` + `parseMaterials(...)` (accepteert lijst én losse waarde, logt onbekende blokken).

**Nieuwe teksten** komen in `src/main/resources/messages.yml` onder `<event-id>:`. Ontbrekende keys vallen terug op de jar-versie, maar een key die nergens bestaat toont `[ontbrekend bericht: …]` in-game — check dus elke `m("…")`/`r("…")` tegen `messages.yml`.

**Locaties** worden opgeslagen door `EventCommand` (`/event setup <id> <key>` → `<id>.<key>`). `pos*`-keys slaan het bekeken blok op, andere keys je eigen positie inclusief kijkrichting.

---

## Scheduling — patronen in dit project

| Task | Interval | Gebruik |
|---|---|---|
| `countdownTask` | `runTaskTimer(…, 0L, 20L)` | Lobby-countdown, cancel als spelers onder `min-players` zakken |
| `gameTask` | `runTaskTimer(…, 20L, 20L)` | Spelloop per seconde: bossbar, timers, rondes, void-check |
| `stepTask`/`fallTask` | 1–4 ticks | Snelle checks: blok onder voeten (TNT Run), checkpoints (Parkour/Elytra), vallen (Spleef) |
| `endTask` | `runTaskLater(…, winDelay * 20L)` | `stop(false)` na de winnaar-melding |
| Setup-preview | `runTaskTimer` met `BukkitTask[] task` + teller | Tijdelijke deeltjes; stopt zichzelf na N keer of als de speler offline is |

1 tick = 50 ms. **Eén** gedeelde task die over `players()`/de alive-set loopt — nooit een task per speler.

Een tick-methode begint altijd met `if (gameOver || state != EventState.RUNNING) return;` — een task kan nog één keer vuren in dezelfde tick als `stop()`.

---

## Shutdown-volgorde (`onStop(boolean forced)`)

1. **Cancel alle tasks** (`countdownTask`, `gameTask`, `stepTask`, `endTask`, …) en zet ze op `null`.
2. **Herstel de wereld** (`arena.restore()`), verwijder gespawnde entities.
3. **Verberg de bossbar** voor `players()`.
4. **Wis eigen state** (alive-set, taggers, cooldown-maps) en zet `gameOver = true`.
5. **`HandlerList.unregisterAll(this)`**.
6. **Melding** alleen bij `forced` (`announce(m("stopped"))`).

Daarna zet `GameEvent.stop()` de spelers terug via hun snapshot, leegt `participants` en meldt het event af bij `EventManager`. Dat gebeurt ook bij `/reload` en server-stop via `EventCorePlugin.onDisable()` → `stopActive()`.

Heeft een event een GUI open bij deelnemers: `closeInventory()` in `onStop()`, vóór de restore.

**Plugin-niveau (`EventCorePlugin.onDisable()`):** `eventScheduler.stop()` → `eventManager.stopActive()` → openstaande DB-writes synchroon afmaken → `databaseManager.disconnect()` als allerlaatste.

---

## Performance

1. **`PlayerMoveEvent`**: eerste regel filtert op deelnemer + fase (`if (!racing(p) || phase != Phase.PREP) return;`). Zware checks (checkpoint-gebieden) horen in een `stepTask`, niet in de move-handler.
2. **Checks per tick alleen over de alive-set**, niet over `Bukkit.getOnlinePlayers()`.
3. **`FloorArena`-volume begrensd** met `max-region-volume`; `takeSnapshot()` en `findSpots()` lopen over het hele gebied — alleen bij start, nooit per tick.
4. **Bulk-restore**: `setBlockData(data, false)` (geen physics), en alleen als het blok echt veranderd is.
5. **Deeltjes**: alleen naar de kijker (`receivers(viewer)`), met een begrensd aantal punten per lijn.
6. **Geen chunk-force-loads** buiten de arena/teleport-bestemming.
7. **Cache immutable objecten** (items, componenten zonder placeholders) als het in een hot path zit.

---

## Debugging — root cause, niet het symptoom

- Speler kwijt inventaris/health/gamemode → staat het in `PlayerSnapshot`? Wordt `leave()`/`stop()` in alle paden bereikt (quit gaat via `EventManager.onQuit`)?
- Event doet iets "terwijl er niets draait" → listener niet afgemeld of task niet gecanceld in `onStop()`, óf code in de constructor (harde regel 2).
- Variant (Hot Potato/One Shot) leest de verkeerde config → hardcoded `"tnttag."` in plaats van `getId()` (harde regel 9).
- Fix de gedeelde oorzaak in `GameEvent`/`PlayerSnapshot`/`FloorArena` als meerdere events hetzelfde probleem hebben, niet in elk event apart.

---

## Snelle diagnose

| Symptoom | Eerste check |
|---|---|
| Speler mist items/health na het event | Veld ontbreekt in `PlayerSnapshot`, of event past iets aan na restore |
| Max health blijft 1 hart na One Shot | `maxHealthBase` in snapshot; volgorde attribute → health in `restore()` |
| Botsen/team blijft na event | `NoCollision.restore()` + oud team in snapshot |
| Listener reageert na afloop / bij volgend event dubbel | `HandlerList.unregisterAll(this)` ontbreekt in `onStop()` |
| Countdown of spel loopt door na `/event stop` | Task niet als field opgeslagen of niet gecanceld |
| Iets gebeurt bij tab-complete of `/event setup` | Side-effects in de constructor van het event |
| `[ontbrekend bericht: …]` in-game | Key mist in `messages.yml` (resource), of verkeerd id-prefix |
| Nieuwe config-optie verschijnt niet op server | Niet toegevoegd aan `src/main/resources/config.yml` |
| Variant gebruikt config van het basis-event | Hardcoded id-string i.p.v. `getId()` |
| Vloer niet hersteld na stop | `takeSnapshot()` niet aangeroepen vóór het spel, of `restore()` niet in `onStop()`; blok buiten gebied zonder `remember()` |
| Spelers kunnen arena-blokken niet breken op server met WorldGuard | Handler niet op `HIGHEST` + `ignoreCancelled = false` + `setCancelled(false)` |
| Spelers breken blokken buiten de arena | `setCancelled(false)` zonder `arena.contains(block)`-check |
| `ArrayIndexOutOfBoundsException` in `/event` | `args.length` niet gecheckt vóór `args[n]` |
| NPE bij start | Setup-locatie niet gecheckt in `validate()` |
| TPS-dip bij start van Spleef/TNT Run | Gebied te groot; `max-region-volume` of snapshot over te veel blokken |
| Server hapert bij einde event / opstarten | JDBC op main (save of `CREATE TABLE` niet via `runTaskAsynchronously`) |
| Nieuwe kolom bestaat niet op live server | Alleen `CREATE TABLE IF NOT EXISTS`, geen `ADD COLUMN IF NOT EXISTS`-migratie |
| `NoClassDefFoundError` voor Hikari/triumph | Shadow-jar niet gebruikt, of library als `compileOnly` i.p.v. `implementation` |
| Conflict met SkyblockCore op dezelfde server | Library niet gereloceerd onder `nl.eventcore.libs.*` |
| `cannot find symbol: class Getterpublic` | `@Getter` en `public` op dezelfde regel |
| `@Getter` wordt niet herkend | `import lombok.Getter;` ontbreekt, of Lombok niet als `annotationProcessor` |
| Spelers pakken items uit een menu | `disableAllInteractions()` vergeten |
| Knoppen in menu doen niets tijdens event | Event-listener cancelt `InventoryClickEvent` ook voor de GUI |

---

## References

| Bestand | Inhoud |
|---|---|
| [`references/event-patterns.md`](references/event-patterns.md) | Skelet voor een nieuw event, fases, varianten, rewards, registratie |
| [`references/command-patterns.md`](references/command-patterns.md) | `/event`-subcommands, `setupKeys`/`handleSetup`/`setupArgs`, arg-validatie, cooldowns |
| [`references/arena-and-blocks.md`](references/arena-and-blocks.md) | `FloorArena`, wereld herstellen, bulk block-changes, gebieden/regio's |
| [`references/item-security.md`](references/item-security.md) | Event-items (reset/skip/tag), inventaris dichtzetten, PDC vs. materiaal |
| [`references/testing.md`](references/testing.md) | Wat je test (pure logica) en in-game testplan per event |
| [`references/libraries-setup.md`](references/libraries-setup.md) | Lombok, HikariCP/MariaDB en triumph-gui toevoegen: build.gradle.kts, shadow, relocatie, database-config, Lombok-regels |
| [`references/database-async.md`](references/database-async.md) | DatabaseManager, tabellen + migraties, event-resultaat wegschrijven, laden met callback, offline database, shutdown |
| [`references/player-state.md`](references/player-state.md) | Event-staat vs. snapshot vs. blijvende data, stats-cache, reconnect-veiligheid |
| [`references/gui-patterns.md`](references/gui-patterns.md) | triumph-gui met Adventure, menu's tijdens een event, leaderboard uit de database, paginering |

Lees het relevante reference-bestand vóór je een nieuw event, setup-optie of arena-logica schrijft.

---

## NMS — alleen als laatste redmiddel

Er is geen NMS/reflection in dit project en dat moet zo blijven. Volgorde: Paper API → Adventure API → pas daarna NMS, met comment welke versie en waarom.

## Build-regels

- `compileOnly` voor Paper API — nooit `implementation`.
- Lombok: `compileOnly` + `annotationProcessor`.
- HikariCP, MariaDB, triumph-gui: `implementation` + shadow-plugin + relocatie onder `nl.eventcore.libs.*` (zie `references/libraries-setup.md`).
- Geen andere nieuwe dependencies zonder reden.
- Een optionele plugin (PlaceholderAPI, Vault, …) als `softdepend` in `plugin.yml` + `Bukkit.getPluginManager().getPlugin("X") != null`-check vóór elk gebruik.
