# GUI-patronen — EventCore (triumph-gui)

> EventCore heeft nog geen GUI. Voeg triumph-gui toe volgens `libraries-setup.md`. Typische toepassingen: `/event`-menu (join, lijst, volgend event), leaderboard, kit- of mapkeuze in de lobby, admin-menu voor start/stop.

EventCore gebruikt **Adventure + MiniMessage**, geen `&`-codes. Titels en itemnamen komen uit `messages.yml` als `Component` via `plugin.messages().get(...)` — triumph-gui 3.1 accepteert `Component` direct.

---

## Basis-GUI

```java
public final class EventMenu {

    private static final int ROWS = 3;
    private static final int SLOT_COUNT = ROWS * 9;
    private static final int JOIN_SLOT = 13;

    private final EventCorePlugin plugin;

    public EventMenu(EventCorePlugin plugin) { this.plugin = plugin; }

    private Component t(String key, Object... kv) {
        return plugin.messages().get("menu." + key, kv).decoration(TextDecoration.ITALIC, false);
    }

    public void open(Player player) {
        Gui gui = Gui.gui()
                .title(t("title"))
                .rows(ROWS)
                .disableAllInteractions()          // VERPLICHT — anders pakken spelers items uit de GUI
                .create();

        gui.getFiller().fill(ItemBuilder.from(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty()).asGuiItem());

        GameEvent active = plugin.getEventManager().active().orElse(null);
        if (active != null) {
            gui.setItem(JOIN_SLOT, ItemBuilder.from(Material.LIME_CONCRETE)
                    .name(t("join", "event", active.getDisplayName()))
                    .lore(t("join-lore", "count", active.getPlayerCount()))
                    .asGuiItem(e -> {
                        player.closeInventory();
                        player.performCommand("event join");   // zelfde pad als het command, geen tweede implementatie
                    }));
        }
        gui.open(player);
    }
}
```

- Click-handlers draaien op de main thread — Bukkit-API is daar veilig. Een `runTask` is alleen nodig na async werk (bv. data uit de database).
- Een knop die hetzelfde doet als een command, gebruikt hetzelfde codepad (de manager-methode of het command), met dezelfde permissie- en state-checks.
- Alle teksten in `messages.yml` onder `menu.*` (of `<event-id>.menu-*` voor een event-specifiek menu).

---

## GUI tijdens een event

De inventaris van deelnemers is dichtgezet (`InventoryClickEvent` gecanceld in elk event). Een triumph-GUI regelt zijn eigen clicks, maar **jouw** event-listener cancelt ze ook — dan werkt geen enkele knop. Twee opties:

1. Open de GUI alleen in de lobby, en laat de event-listener clicks in een triumph-GUI doorlaten:
   ```java
   @EventHandler(ignoreCancelled = true)
   public void onInventoryClick(InventoryClickEvent e) {
       if (!inEvent(e.getWhoClicked())) return;
       if (e.getInventory().getHolder() instanceof BaseGui) return;   // triumph regelt dit zelf
       e.setCancelled(true);
   }
   ```
2. Gebruik in het spel zelf geen GUI, maar een item met rechtsklik (zie `item-security.md`).

Een GUI die openstaat als het event stopt: sluit hem in `onStop()` (`p.closeInventory()` voor `players()`), vóór de snapshot-restore.

---

## Leaderboard met data uit de database

```java
public void open(Player player, String eventId) {
    UUID uuid = player.getUniqueId();
    stats.loadTop(eventId, 10, top -> {           // callback draait al op main
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;                    // intussen offline
        Gui gui = Gui.gui().title(t("top-title", "event", eventId)).rows(3).disableAllInteractions().create();
        for (int i = 0; i < top.size(); i++) {
            TopEntry entry = top.get(i);
            gui.setItem(10 + i, ItemBuilder.from(Material.PLAYER_HEAD)
                    .name(t("top-entry", "place", i + 1, "player", entry.name(), "wins", entry.wins()))
                    .asGuiItem());
        }
        gui.open(p);
    });
}
```

Nooit een DB-query in een click-handler — laad async en open daarna.

---

## Paginering

`PaginatedGui` van triumph (`Gui.paginated().rows(6).pageSize(45).create()`), met `previous()`/`next()` op twee navigatieknoppen. Toon vorige/volgende alleen als er een vorige/volgende pagina is.

---

## Identificatie & state

- Identificeer een GUI nooit op titel. triumph-gui koppelt de click-lambda al aan het item; voor een eigen `Inventory` gebruik je een identity-check (`inventory == stored`) of `getHolder() instanceof`.
- Multi-step keuzes (event kiezen → map kiezen → bevestigen): geef de gekozen waarden als parameters door aan de volgende `open(...)`, nooit via een gedeeld/static veld.
- Houdt een GUI tijdelijke state bij (pending bevestiging)? Ruim die op in `gui.setCloseGuiAction(...)` — ESC sluit ook.
- Knoppen die iets toekennen of starten: cooldown per speler (zie `command-patterns.md`).

---

## Checklist

- [ ] `disableAllInteractions()` op elke GUI
- [ ] Titels/namen als `Component` uit `messages.yml`, italic uit, geen `&`-codes
- [ ] Knoppen gebruiken hetzelfde codepad en dezelfde checks als het command
- [ ] Event-listener laat triumph-GUI-clicks door (of geen GUI tijdens het spel)
- [ ] GUI gesloten in `onStop()` als deelnemers hem open kunnen hebben
- [ ] Geen DB-call in click-handlers; async laden → callback → open met online-check
- [ ] Geen titel-vergelijking, geen static GUI-state
