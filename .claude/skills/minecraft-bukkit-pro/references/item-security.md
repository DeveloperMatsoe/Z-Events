# Event-items & inventaris — EventCore

## Context: de inventaris is tijdens een event dichtgezet

`PlayerSnapshot.prepare()` leegt de inventaris bij join, en events cancelen voor deelnemers standaard `PlayerDropItemEvent`, `PlayerSwapHandItemsEvent`, `InventoryClickEvent`, `BlockPlaceEvent` en `BlockBreakEvent` (behalve waar het spel het nodig heeft). Na afloop zet `PlayerSnapshot.restore()` de oude inventaris terug — alles wat de speler tijdens het event kreeg, verdwijnt.

Dat betekent:
- **Een event-item kan niet "ontsnappen"** naar de echte inventaris, zolang restore altijd draait. Dat is de belangrijkste dupe-bescherming: houd `leave()`/`stop()` in alle paden intact (quit, stop, reload, shutdown).
- **Laat de standaard-cancels staan** bij een nieuw event (zie het `Listeners`-blok onderaan `TntTagEvent`). Haal er alleen één weg als het spel het echt vereist, en dan zo smal mogelijk.

---

## Herkennen van event-items: materiaal vs. PDC

Nu herkennen events hun items op `Material` (`RED_DYE` = reset, `IRON_INGOT` = skip in Dropper). Dat werkt omdat alleen de plugin items in de event-inventaris zet. **Uitzondering:** `/event give` en de kit (`lms.kit`) kunnen elk item geven — een admin die `red_dye` uitdeelt in Parkour maakt per ongeluk een reset-item.

Voor een nieuw event-item, of als je een bestaand item aanpast:

```java
private static final NamespacedKey ITEM_KEY = new NamespacedKey("eventcore", "item");   // static final, niet per event

private ItemStack resetItem() {
    ItemStack item = new ItemStack(Material.RED_DYE);
    item.editMeta(meta -> {
        meta.displayName(r("reset-item").decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(ITEM_KEY, PersistentDataType.STRING, "reset");
    });
    return item;
}

private boolean isResetItem(ItemStack item) {
    return item != null && item.hasItemMeta()
            && "reset".equals(item.getItemMeta().getPersistentDataContainer().get(ITEM_KEY, PersistentDataType.STRING));
}
```

- Herken nooit op display name of lore.
- De PDC-waarde bevat het soort item (`reset`, `skip`, `tag`), zodat één key volstaat.

---

## Acties met een item

- `PlayerInteractEvent`: negeer `Action.PHYSICAL`, check deelnemer, cancel het event, en dán pas de actie (zie `ParkourEvent.onInteract`).
- Rechtsklik vuurt vaak twee keer (main + offhand, of twee pakketjes vlak na elkaar) → cooldown-map per speler (zie `command-patterns.md`).
- Een item dat maar één keer gebruikt mag worden (skip): haal het **eerst** weg, voer dan de actie uit.

---

## Kits en `/event give`

- Altijd via `ItemGiver.parseItem(...)` + `ItemGiver.give(...)`; ongeldige regels in een kit loggen op `warning` en overslaan, niet het hele event laten falen.
- `giveTargets()` overschrijven als toeschouwers/uitgeschakelde spelers niets mogen krijgen (zoals LMS doet).

---

## Checklist

- [ ] Standaard inventaris-cancels aanwezig voor deelnemers
- [ ] Nieuwe event-items herkend via PDC, niet via naam/lore
- [ ] Item-acties: deelnemer-check, cancel, cooldown, eenmalige items eerst weghalen
- [ ] Geen pad waarin een speler het event verlaat zonder `leave()`/`stop()` (en dus zonder restore)
