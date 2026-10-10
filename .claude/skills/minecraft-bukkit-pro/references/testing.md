# Testen — EventCore

## Status

Er is geen test-setup (`src/test` bestaat niet, geen JUnit in `build.gradle.kts`). Voeg het pas toe als er pure logica is die het waard is om te testen — niet preventief.

---

## Wat je wel unit-test

Logica zonder Bukkit-runtime:
- Parsen van config-waarden (tijden, dagen en rotatie in `EventScheduler`, materiaallijsten in `FloorArena.readNames`)
- Rekenwerk: spreiden van spawnpunten, plekken/ranking, aftellen, tagger-aantallen (`taggerCount`)
- `ItemGiver.isValidSlot(...)`

Haal zo'n stuk logica eventueel naar een kleine `static`-methode of een `record` zonder Bukkit-imports, zodat het zonder MockBukkit te testen is.

## Wat je niet unit-test

- Listeners en de spelloop van events — dat test je in-game
- Bossbar, titels, deeltjes

---

## Opzet (pas als het eerste stuk logica getest wordt)

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")   // check laatste versie
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

MockBukkit alleen als de geteste klasse echt `Bukkit.*` aanroept, en alleen als er een versie voor Paper 26.2 is.

---

## In-game testplan (na elk nieuw of gewijzigd event)

Draai deze lijst op een testserver, met minstens 2 accounts:

1. **Setup**: alle `/event setup <id> …`-opties, tab-complete, `validate()`-melding als er iets ontbreekt.
2. **Lobby**: join, leave, countdown start bij `min-players`, stopt als iemand weggaat, `max-players`.
3. **Spel**: normale ronde tot winnaar; bossbar klopt; beloningen (met `rewards.enabled: true`).
4. **Restore** — het belangrijkste:
   - Join met een volle inventaris, armor, effecten, vliegen aan, in creative → na het event alles terug?
   - Quit midden in het spel → bij opnieuw inloggen alles terug en niet meer in het event?
   - `/event stop` midden in het spel → spelers terug, vloer hersteld, geen tasks die doorlopen?
   - `/reload` of server-stop midden in het spel → idem.
5. **Volgende event**: start direct daarna een ander event — geen dubbele meldingen of listeners van het vorige.
6. **Performance**: met de grootste arena die je verwacht, `/spark tps` of `/mspt` tijdens start en spel.

---

## Checklist

- [ ] Pure logica met niet-triviale berekening heeft een unit test (als er een test-setup is)
- [ ] In-game testplan doorlopen, vooral de restore-paden
