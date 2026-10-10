# EventCore

Paper-plugin voor automatische server-events. Minecraft/Paper **26.2**, **Java 25**.

## Bouwen
```
.\gradlew.bat build
```
De jar staat daarna in `build/libs/`.

De Paper-versie staat vast in `build.gradle.kts` (`paper-api:26.2.build.129-stable`), zodat elke build hetzelfde is.
Nieuwere Paper-build gebruiken? Pas dat nummer aan.

**Let op:** `/event setup ...` slaat `config.yml` op met wat de plugin op dat moment in het geheugen heeft.
Heb je `config.yml` met de hand aangepast, doe dan eerst `/event reload`, anders worden je wijzigingen overschreven.

## Eerste gebruik (TNT Tag)
1. Zet de jar in `plugins/` en start de server (Java 25).
2. Ga in de lobby staan: `/event setup tnttag lobby`
3. Ga in de arena staan: `/event setup tnttag spawn`
4. `/event start tnttag` - spelers doen mee met `/event join` (of klikken op de melding in de chat)
5. `/event stop` stopt het event en zet iedereen terug

## Commands
| Command | Permissie | Uitleg |
|---|---|---|
| `/event join` / `leave` | `eventcore.play` | meedoen / verlaten |
| `/event list` / `next` | `eventcore.play` | events tonen / volgend geplande event |
| `/event start <event>` | `eventcore.admin` | lobby openen |
| `/event stop [event]` | `eventcore.admin` | event stoppen |
| `/event setup <event> <lobby\|spawn>` | `eventcore.admin` | locaties instellen |
| `/event give <speler\|all> <item> [plek] [aantal]` | `eventcore.admin` | item geven, `all` = alle (levende) spelers in het event |
| `/event reload` | `eventcore.admin` | config + messages herladen |

## Spleef
1. Bouw een vloer van sneeuwblokken (1 of meer lagen) boven een leegte of waterbak.
2. Kijk naar een hoekblok van de vloer en voer `/event setup spleef pos1` uit, kijk naar het tegenoverliggende hoekblok (andere laag mag) en voer `/event setup spleef pos2` uit.
3. `/event setup spleef lobby` en `/event setup spleef spawn` (spawn = waar toeschouwers komen).
4. `/event start spleef`. Spelers worden verspreid over de vloer gezet, slaan blokken meteen kapot en vallen eruit. De vloer wordt na afloop hersteld.

## TNT Run
1. Bouw een vloer van zand of gravel, met daaronder een laag TNT (mag ook zonder). Meerdere lagen mag.
2. Kijk naar een hoekblok en voer `/event setup tntrun pos1` uit, kijk naar het tegenoverliggende hoekblok en voer `/event setup tntrun pos2` uit.
3. `/event setup tntrun lobby` en `/event setup tntrun spawn` (spawn = waar toeschouwers komen).
4. `/event start tntrun`. Blokken onder spelers verdwijnen na een halve seconde, wie valt is af. Met `double-jumps` krijgt iedereen een paar dubbele sprongen. De vloer wordt na afloop hersteld.
   Bestaat je vloer alleen uit TNT-blokken? Zet dan `TNT` bij `tntrun.breakable-blocks`.

## Elytra Race
1. Bouw een startplatform in de lucht en een baan met ringen/poorten (de checkpoints zijn onzichtbaar voor de plugin, bouw wat je wilt).
2. Ga op het platform staan, **kijk de vliegrichting op** en voer `/event setup elytrarace start` uit. Daarna `/event setup elytrarace lobby`.
3. Vlieg of ga naar het midden van elk checkpoint, kijk de vliegrichting op en voer `/event setup elytrarace checkpoint add` uit. De volgorde telt.
4. Op dezelfde manier: `/event setup elytrarace finish`.
5. Controleren: `/event setup elytrarace checkpoint show` (10 s ringen zien) of `checkpoint list`. Aanpassen met `checkpoint insert <nr>`, `remove <nr>`, `tp <nr>` of `clear`.
6. `/event start elytrarace`. Iedereen krijgt een elytra en vuurpijlen, de ring van je volgende checkpoint is groen, de finish geel.
   Raak je na het opstijgen de grond of water, dan ga je terug naar je laatste checkpoint.

Met `elytrarace.winners` kies je hoeveel plekken er zijn (1 = alleen een winnaar, 3 = top 3). De race stopt zodra die plekken vergeven zijn,
iedereen binnen is, of `max-duration-seconds` verstreken is. Beloningen per plek staan bij `elytrarace.rewards.places`.

## Last Man Standing
1. Bouw een kleine arena. Kijk naar een hoekblok en voer `/event setup lms pos1` uit, kijk naar het tegenoverliggende hoekblok (vloer tot plafond) en voer `/event setup lms pos2` uit.
2. `/event setup lms lobby` en `/event setup lms spawn` (spawn = waar uitgeschakelde spelers toekijken).
3. Stel de kit in bij `lms.kit` in `config.yml`.
4. `/event start lms`. Spelers worden zo ver mogelijk uit elkaar gezet, staan 5 seconden stil en vechten daarna tot er één over is.
   Wie uit de arena valt is af; zijwaarts de arena uit lopen kan niet. Na `glow-after-seconds` licht iedereen op.

## One Shot
Zoals Last Man Standing, maar iedereen heeft maar **1 hart** (`oneshot.hearts`). Met `one-hit-kill: true` is elke klap of pijl van een andere speler meteen raak,
en met `arrows-per-kill` krijg je per kill een pijl terug. Je stelt een eigen arena in met `/event setup oneshot pos1|pos2|lobby|spawn`
(mag dezelfde arena zijn als bij lms) en een eigen kit bij `oneshot.kit`. Starten met `/event start oneshot`.
Na afloop krijgt iedereen zijn normale aantal harten terug.

## Hot Potato
Zoals TNT Tag, maar per ronde heeft **1 speler** (`hotpotato.holders`) de hete aardappel en ontploft hij na een **willekeurige tijd**
tussen `fuse-min-seconds` en `fuse-max-seconds`. Die tijd is nergens te zien: geen aftellen, de bossbar blijft vol en het tikgeluid is altijd hetzelfde.
Instellen: `/event setup hotpotato lobby` en `/event setup hotpotato spawn`, starten met `/event start hotpotato`.

## Dropper
Een dropper bestaat uit maps 1, 2, 3, ... Iedereen begint op de start van map 1. Val je door een portal van map 1, dan ga je naar de start van map 2, enzovoort.
Raak je een finish (welke dan ook), dan ben je binnen. Valschade = terug naar de start van je map; met de rode kleurstof (rechtsklik) begin je zelf opnieuw als je vastzit.
Ben je `skip-after-fails` keer (standaard 10) mis op dezelfde map, dan krijg je een ijzerstaaf: rechtsklik = die map overslaan. De laatste map kun je niet overslaan.
1. `/event setup dropper lobby`
2. Per map: ga op de start staan, kijk de goede kant op en voer `/event setup dropper start <map>` uit.
3. Ga midden onderin elke portal staan en voer `/event setup dropper portal <map>` uit. Meerdere portals in een map? Gewoon vaker uitvoeren met hetzelfde nummer.
   Het gebied is zo groot als een netherportal (`portal-radius` 2 blokken opzij, `portal-height` 3 hoog). Een echte netherportal mag, die stuurt je niet naar de Nether.
4. Ga op de finish staan: `/event setup dropper finish 1` (en eventueel `finish 2`, `finish 3`, ...).
5. Controleren met `/event setup dropper list` of `show` (10 s alle gebieden zien). Weghalen met `clear <start|portal|finish> <nr>` (bij portal: alle portals van die map).
6. `/event start dropper`. De eerste `winners` spelers krijgen een plek, met tijd en aantal keer mis.

## Parkour Race
Van de spawn via de checkpoints naar de finish; de eerste bij de finish wint (`parkour.winners`, bv. 3 voor een top 3).
1. `/event setup parkour lobby`, `/event setup parkour spawn` (kijk de goede kant op) en `/event setup parkour finish`.
2. Ga op elk checkpoint staan en voer `/event setup parkour checkpoint add` uit, op volgorde. Aanpassen met `insert|remove|tp <nr>`, `list` of `clear`.
3. Controleren met `/event setup parkour checkpoint show` (10 s alle gebieden zien).
4. `/event start parkour`. Val je (te diep, in water of lava), dan ga je terug naar je laatste checkpoint. Een checkpoint verderop telt ook.
   Met de rode kleurstof (rechtsklik) ga je zelf terug naar je laatste checkpoint. Er is geen schade.

## Items geven
`/event give <speler|all> <item> [plek] [aantal]` - bijvoorbeeld midden in Last Man Standing iedereen een beter zwaard geven:
```
/event give all diamond_sword mainhand
/event give all diamond_sword[enchantments={sharpness:3}] mainhand
/event give Steve golden_apple inventory 3
```
- Item: zoals bij `/give`, dus ook met componenten zoals enchantments.
- Plek: `helmet`, `chestplate`, `leggings`, `boots`, `offhand`, `mainhand` of `inventory` (eerstvolgende vrije plek, standaard). Bij een vaste plek wordt vervangen wat er al lag.
- `all` = iedereen in het actieve event (bij Last Man Standing alleen wie nog leeft).
- De kit in `lms.kit` gebruikt dezelfde schrijfwijze: `"<item> [plek] [aantal]"`.

## Automatisch plannen
Zet in `config.yml` bij `schedule` de optie `enabled: true`, kies `times` (bv. `["18:00", "20:30"]`),
eventueel `days` en `mode` (`rotation` of `random`). Een aankondiging volgt `announce-minutes-before` minuten eerder.
Komen er binnen `general.lobby-timeout-minutes` te weinig spelers, dan sluit de lobby vanzelf.

## Beloningen
`tnttag.rewards` in `config.yml`: console-commando's voor de winnaar en voor alle deelnemers, met `%player%`.

## Crash tijdens een event
Bij het joinen wordt de inventaris/staat van elke speler ook op schijf bewaard (`plugins/EventCore/recovery/players/`),
en bij de start van Spleef en TNT Run de vloer (`recovery/arena-<event>.txt`).
Valt de server midden in een event uit, dan wordt bij de volgende start de vloer hersteld en krijgt elke speler bij het
inloggen zijn spullen terug. Na een nette stop of `/reload` zijn deze bestanden er niet. Een bestand dat niet
teruggezet kon worden blijft staan (of wordt bewaard als `.yml.old`); kijk dan in de console.

## Teksten aanpassen
Alle teksten staan in `plugins/EventCore/messages.yml` (MiniMessage). Daarna `/event reload`.

## Nieuw event toevoegen
1. Maak een klasse die `GameEvent` extend (zie `TntTagEvent`).
2. Registreer hem in `EventCorePlugin#onEnable` met `eventManager.register("id", () -> new MijnEvent(this))`.

## Events
- TNT Tag (`tnttag`)
- Spleef (`spleef`)
- TNT Run (`tntrun`)
- Elytra Race (`elytrarace`)
- Last Man Standing (`lms`)
- One Shot (`oneshot`)
- Hot Potato (`hotpotato`)
- Dropper (`dropper`)
- Parkour Race (`parkour`)

## Roadmap TNT Tag
- Deel 1 (klaar): framework, commands, lobby, countdown, start
- Deel 2 (klaar): tikken, lont + ontploffing, rondes, eliminatie, winnaar
- Deel 3 (klaar): bossbar, beloningen, messages.yml, automatisch plannen
#   Z - E v e n t s  
 