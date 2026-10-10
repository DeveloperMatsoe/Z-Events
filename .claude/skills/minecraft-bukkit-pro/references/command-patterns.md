# Command-patronen — EventCore

## Eén command: `/event`

Er is één command (`event` in `plugin.yml`) met subcommands in `EventCommand.onCommand()` via een `switch` op `args[0].toLowerCase(Locale.ROOT)`. Voeg een nieuw subcommand daar toe — niet een nieuw command in `plugin.yml`, tenzij het echt los van events staat.

Per nieuw subcommand:
1. `case "naam" -> { ... }` in `onCommand`.
2. Permissie checken: `if (!sender.hasPermission(ADMIN)) return noPerm(sender);`.
3. Speler nodig? `if (!(sender instanceof Player p)) { sender.sendMessage(m().prefixed("general.players-only")); return true; }`.
4. `args.length` checken vóór elke `args[n]`, met een usage-bericht uit `messages.yml` (`command.<naam>-usage`).
5. Tab-complete toevoegen in `onTabComplete` (achter de juiste permissie).
6. Regel toevoegen aan `help.player` of `help.admin` in `messages.yml`, en aan de tabel in `README.md`.

```java
case "naam" -> {
    if (!sender.hasPermission(ADMIN)) return noPerm(sender);
    if (args.length < 2) {
        sender.sendMessage(m().prefixed("command.naam-usage"));
        return true;
    }
    // ... args[1]
}
```

Getallen parsen: `try { Integer.parseInt(args[n]) } catch (NumberFormatException e) { usage; return; }` — zie `give()`.

---

## Setup-opties per event

`/event setup <event> <optie> [args]` loopt zo:

1. `EventCommand` checkt of `<optie>` in `event.setupKeys()` staat.
2. `event.handleSetup(player, key, restArgs)` — geeft het event `true` terug, dan is het klaar.
3. Anders slaat `EventCommand` een locatie op onder `<event>.<optie>`: bij `pos*` het bekeken blok, anders je positie (met kijkrichting).

Dus:

| Je wilt… | Doe |
|---|---|
| Een extra locatie (bv. `finish`) | Voeg toe aan `setupKeys()`; verder niets |
| Een gebied (`pos1`/`pos2`) | Keys beginnen met `pos` → automatisch blok-locatie |
| Een lijst (checkpoints, portals per map) of subcommands | `handleSetup()` overschrijven + `setupArgs()` voor tab-complete |

```java
@Override
public List<String> setupKeys() {
    return List.of("lobby", "spawn", "finish", "checkpoint");
}

@Override
public boolean handleSetup(Player player, String key, String[] args) {
    if (!key.equals("checkpoint")) return false;          // de rest: gewoon locatie opslaan
    if (args.length == 0) {
        player.sendMessage(m("checkpoint-usage"));
        return true;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
        case "add" -> { ... plugin.saveConfig(); }
        case "remove" -> {
            if (args.length < 2) { player.sendMessage(m("checkpoint-usage")); return true; }
            ...
        }
        default -> player.sendMessage(m("checkpoint-usage"));
    }
    return true;
}

@Override
public List<String> setupArgs(String key) {
    return key.equals("checkpoint") ? List.of("add", "insert", "remove", "tp", "list", "show", "clear") : List.of();
}
```

Let op: `handleSetup` draait op een **wegwerp-instantie** (de factory wordt opnieuw aangeroepen). Lees en schrijf alles direct via `plugin.getConfig()` + `plugin.saveConfig()`, niet via velden van het event. Lijsten van locaties: `cfg.set(path, List<Location>)` en teruglezen met `cfg.getList(path)` + `instanceof Location`-filter.

Een setup-preview (`show`) tekent tijdelijk deeltjes alleen voor de admin en stopt zichzelf — zie `ParkourEvent.showAll()`.

---

## Cooldown / anti-spam

Voor acties die spelers kunnen spammen (reset-item, skip, tikken): `Map<UUID, Long>` met "mag weer vanaf"-tijd, als veld van het event.

```java
long now = System.currentTimeMillis();
if (resetCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
resetCooldown.put(p.getUniqueId(), now + 1000L);
```

Omdat een event-instantie maar één event leeft, hoeft deze map niet periodiek opgeruimd te worden; wel `clear()` in `onStop()` en `remove(uuid)` in `onLeave()`.

---

## Checklist

- [ ] `args.length` gecheckt vóór elke `args[n]`, met usage-bericht uit `messages.yml`
- [ ] Permissie (`eventcore.admin`/`eventcore.play`) gecheckt
- [ ] Tab-complete bijgewerkt, ook in `setupArgs()`
- [ ] Help-regel in `messages.yml` en tabel in `README.md` bijgewerkt
- [ ] `handleSetup` gebruikt geen instantievelden en doet `saveConfig()` na wijzigen
