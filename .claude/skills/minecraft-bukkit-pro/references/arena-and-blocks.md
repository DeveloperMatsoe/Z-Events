# Arena's & blokken — EventCore

## Regel: wat een event verandert, zet het terug

Events slopen vloeren (Spleef, TNT Run). De wereld moet na afloop exact terug zijn — ook bij `/event stop`, een crash van het spel of een server-stop (`onDisable` → `stopActive()` → `onStop(true)`).

`FloorArena` is het mechanisme hiervoor. Gebruik het, verzin geen tweede.

```java
// onOpen() of beginGame():
Location p1 = cfg.getLocation(getId() + ".pos1");
Location p2 = cfg.getLocation(getId() + ".pos2");
// validate(): beide niet null, zelfde wereld, en volume binnen max-region-volume
Set<Material> breakable = FloorArena.parseMaterials(
        FloorArena.readNames(cfg, getId() + ".breakable-blocks"), Material.SNOW_BLOCK,
        plugin.getLogger(), getId() + ".breakable-blocks");
arena = new FloorArena(p1, p2, breakable);
arena.takeSnapshot();

// tijdens het spel, een blok buiten pos1/pos2 dat toch verandert (bv. TNT eronder):
arena.remember(block);
block.setType(Material.AIR, false);

// onStop():
if (arena != null) arena.restore();
```

- **`validate()` checkt het volume** met `FloorArena.volume(p1, p2)` tegen `max-region-volume`. `takeSnapshot()` en `findSpots()` lopen over elk blok in het gebied.
- **Zelfde wereld**: pos1 en pos2 moeten in dezelfde wereld liggen.
- **`remember()` vóór de wijziging**, niet erna — anders sla je de nieuwe staat op.
- **Restore vóór de spelers teruggezet worden**: `onStop()` draait vóór de snapshot-restore in `GameEvent.stop()`, dus de vloer is terug voordat iemand terug teleporteert.

---

## Gebieden zonder blokken (checkpoints, portals, finish)

Races gebruiken onzichtbare gebieden rond een opgeslagen locatie. Patroon (`ParkourEvent`, `DropperEvent`): een `BoundingBox` met `radius` opzij en `height` omhoog vanaf de voeten, en een check in een `stepTask` (1–2 ticks):

```java
private static BoundingBox area(Location c, double radius, double height) {
    return new BoundingBox(c.getX() - radius, c.getY(), c.getZ() - radius,
                           c.getX() + radius, c.getY() + height, c.getZ() + radius);
}
```

- Check ook de wereld (`p.getWorld().equals(c.getWorld())`).
- Radius en hoogte zijn config-opties met een minimum (`Math.max(0.5, …)`).
- Check alleen spelers die nog racen, niet iedereen in het event.

---

## Bulk block-changes

- `setBlockData(data, false)` / `setType(mat, false)` — geen physics-cascade bij restore en bij het weghalen van vloerblokken.
- Restore alleen blokken die echt veranderd zijn (`!b.getBlockData().equals(e.data())`), zoals `FloorArena.restore()` al doet.
- Gaat het om tienduizenden blokken in één keer, spreid het dan over ticks met een `runTaskTimer` die N blokken per tick doet — en laat `onStop()` het restant synchroon afmaken, want bij shutdown is er geen volgende tick.

---

## Blok-events van andere plugins overrulen

Op servers met region-protection wordt `BlockBreakEvent` vaak al gecanceld vóór EventCore het ziet. Spleef lost dat zo op:

```java
@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
public void onBreak(BlockBreakEvent e) {
    Player p = e.getPlayer();
    if (!isParticipant(p)) return;
    if (canBreak(p, e.getBlock())) {        // RUNNING, PLAY-fase, arena.contains(block), breakable materiaal
        e.setCancelled(false);
        e.setDropItems(false);
        e.setExpToDrop(0);
    } else {
        e.setCancelled(true);
    }
}
```

Un-cancel **nooit** zonder `arena.contains(block)` — anders kan een deelnemer met de schep blokken buiten de arena slopen die niet in de snapshot staan.

---

## `Block` is een live handle

Een `Block` geeft altijd de huidige staat. Wil je de oude staat bewaren: `block.getBlockData()` (wat `FloorArena` opslaat) of `block.getState()`, nooit de `Block`-referentie zelf.

---

## Checklist

- [ ] Elk veranderd blok staat in de snapshot of is via `remember()` vastgelegd vóór de wijziging
- [ ] `arena.restore()` in `onStop()`, met null-check
- [ ] `validate()` checkt pos1/pos2 aanwezig, zelfde wereld, volume binnen de limiet
- [ ] Un-cancel van blok-events alleen binnen de arena en voor toegestane materialen
- [ ] Physics uit bij bulk-wijzigingen
