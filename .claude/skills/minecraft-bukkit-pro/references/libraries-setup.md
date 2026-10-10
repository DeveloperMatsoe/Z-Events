# Libraries toevoegen — EventCore (Lombok, HikariCP/MariaDB, triumph-gui)

EventCore heeft deze libraries **nog niet**. Zodra een taak er één nodig heeft, voeg je ze toe zoals hieronder — dezelfde versies en dezelfde opzet als SkyblockCore, zodat de projecten gelijk blijven. Voeg alleen toe wat de taak echt gebruikt.

---

## build.gradle.kts

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"     // alleen nodig voor runtime-libraries (Hikari, MariaDB, triumph)
}

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://repo.triumphteam.dev/releases/")       // alleen voor triumph-gui
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // Lombok — alleen compile-time, komt niet in de jar
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    // Database
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")

    // GUI
    implementation("dev.triumphteam:triumph-gui:3.1.11")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("com.zaxxer.hikari", "nl.eventcore.libs.hikari")
        relocate("org.mariadb", "nl.eventcore.libs.mariadb")
        relocate("dev.triumphteam", "nl.eventcore.libs.triumphteam")
    }
    build {
        dependsOn(shadowJar)
    }
    // bestaande processResources / compileJava blijven staan
}
```

Regels:
- **Elke** geshadede library wordt gereloceerd onder `nl.eventcore.libs.*` — anders botst hij met dezelfde library in SkyblockCore/NationCore/SurvivalCore op dezelfde server.
- Paper blijft `compileOnly`. Lombok blijft `compileOnly` + `annotationProcessor`.
- Na het toevoegen van shadow is de jar die je uploadt de shadow-jar in `build/libs/` (zonder classifier door `archiveClassifier.set("")`). Werk `README.md` → "Bouwen" bij als dat verandert.
- Check bij een Paper-update of de versies nog werken; zet dezelfde versies als in SkyblockCore tenzij er een reden is om af te wijken.

---

## config.yml — database-sectie

```yaml
# Database (MariaDB/MySQL). Uit = geen statistieken/leaderboards, de events werken gewoon.
database:
  enabled: false
  host: "localhost"
  port: 3306
  name: "eventcore"
  user: "eventcore"
  password: ""
  pool-size: 5
  table-prefix: "ec_"
```

`database.enabled: false` is de default: EventCore moet zonder database blijven werken. Elke DB-feature degradeert netjes als de database uit of offline is (zie `database-async.md`).

---

## Lombok-gebruik in dit project

- `import lombok.Getter;` is verplicht — zonder import ziet de compiler `@Getter` niet.
- `@Getter` op een **eigen regel**, nooit op dezelfde regel als `public`/`private` (geeft rare fouten zoals `cannot find symbol: class Getterpublic` bij sommige tools).
- Gebruik Lombok voor nieuwe data-klassen (`@Getter`, eventueel `@RequiredArgsConstructor`). Bestaande klassen (`GameEvent`, `EventManager`, …) hoef je niet om te bouwen — alleen als je ze toch al ingrijpend wijzigt.
- Geen `@Data` op klassen met collections of mutable state: genereert `equals/hashCode` over alle velden en setters voor alles. Liever `@Getter` + expliciete methodes.
- Geen `@Getter` op een veld met een interne `Map`/`Set`/`List` — schrijf de getter zelf en geef een kopie terug (`Map.copyOf(...)`, `List.copyOf(...)`).
- Voor immutable value types liever een Java `record` dan Lombok.

```java
import lombok.Getter;

public final class EventStats {

    @Getter
    private final UUID uuid;

    @Getter
    private int wins;

    ...
}
```
