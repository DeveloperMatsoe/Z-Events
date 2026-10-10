package nl.eventcore.event;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.eventcore.EventCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** Start events automatisch op vaste tijden (config: schedule.*). */
public final class EventScheduler {

    private final EventCorePlugin plugin;
    private final EventManager manager;
    private final Random random = new Random();

    private BukkitTask task;
    private final Set<String> done = new HashSet<>();
    private LocalDate doneDate;
    private int rotationIndex = 0;
    private String pending;

    private boolean enabled;
    private ZoneId zone = ZoneId.systemDefault();
    private final List<LocalTime> times = new ArrayList<>();
    private final Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
    private final List<String> pool = new ArrayList<>();
    private boolean randomMode;
    private int announceMinutes;

    public EventScheduler(EventCorePlugin plugin, EventManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() {
        stop();
        load();
        if (!enabled) return;
        // Elke 10 seconden controleren, dat is ruim genoeg nauwkeurig.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 100L, 200L);
        plugin.getLogger().info("Eventschema actief (" + times.size() + " starttijd(en), zone " + zone + ").");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        pending = null;
    }

    private void load() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("schedule.enabled", false);

        try {
            zone = ZoneId.of(cfg.getString("schedule.timezone", "Europe/Amsterdam"));
        } catch (DateTimeException e) {
            plugin.getLogger().warning("Ongeldige tijdzone in schedule.timezone, systeemtijdzone wordt gebruikt.");
            zone = ZoneId.systemDefault();
        }

        times.clear();
        for (String s : cfg.getStringList("schedule.times")) {
            try {
                times.add(LocalTime.parse(s.trim()));
            } catch (DateTimeParseException e) {
                plugin.getLogger().warning("Ongeldige tijd in schedule.times: '" + s + "' (gebruik bv. 18:00)");
            }
        }
        Collections.sort(times);

        days.clear();
        for (String s : cfg.getStringList("schedule.days")) {
            try {
                days.add(DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Ongeldige dag in schedule.days: '" + s + "' (gebruik bv. SATURDAY)");
            }
        }

        pool.clear();
        for (String s : cfg.getStringList("schedule.events")) {
            if (manager.ids().contains(s.toLowerCase(Locale.ROOT))) pool.add(s.toLowerCase(Locale.ROOT));
            else plugin.getLogger().warning("Onbekend event in schedule.events: '" + s + "'");
        }
        if (pool.isEmpty()) pool.addAll(manager.ids());

        randomMode = "random".equalsIgnoreCase(cfg.getString("schedule.mode", "rotation"));
        announceMinutes = Math.max(0, cfg.getInt("schedule.announce-minutes-before", 5));
        pending = null;
        done.clear();
        doneDate = null;
    }

    private void check() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();

        // Sleutels beginnen met de datum (yyyy-MM-dd); oude dagen opruimen.
        if (!today.equals(doneDate)) {
            String cutoff = today.toString();
            done.removeIf(key -> key.compareTo(cutoff) < 0);
            doneDate = today;
        }

        // Ook morgen bekijken: een start om 00:02 wordt dan om 23:57 de dag ervoor aangekondigd.
        long announceSecs = announceMinutes * 60L;
        for (LocalDate date : List.of(today, today.plusDays(1))) {
            if (!days.isEmpty() && !days.contains(date.getDayOfWeek())) continue;
            for (LocalTime t : times) {
                long secs = Duration.between(now, date.atTime(t).atZone(zone)).getSeconds();
                String key = date + " " + t;

                if (announceMinutes > 0 && secs <= announceSecs && secs > announceSecs - 60 && done.add(key + "-announce")) {
                    pending = choose();
                    Bukkit.broadcast(plugin.messages().prefixed("schedule.announce",
                            "event", manager.displayName(pending), "minutes", announceMinutes));
                }
                if (secs <= 0 && secs > -60 && done.add(key + "-start")) {
                    fire();
                }
            }
        }
    }

    private void fire() {
        String id = pending != null ? pending : choose();
        pending = null;

        if (manager.active().isPresent()) {
            plugin.getLogger().info("Geplande start van '" + id + "' overgeslagen: er draait al een event.");
            return;
        }
        EventManager.Result r = manager.start(id);
        if (!r.ok()) {
            plugin.getLogger().warning("Geplande start van '" + id + "' mislukt: "
                    + PlainTextComponentSerializer.plainText().serialize(r.message()));
        }
    }

    private String choose() {
        if (pool.isEmpty()) return "";
        if (randomMode) return pool.get(random.nextInt(pool.size()));
        String id = pool.get(rotationIndex % pool.size());
        rotationIndex++;
        return id;
    }

    // ---------- voor /event next ----------

    public Optional<ZonedDateTime> nextRun() {
        if (!enabled || times.isEmpty()) return Optional.empty();
        ZonedDateTime now = ZonedDateTime.now(zone);
        for (int d = 0; d < 8; d++) {
            LocalDate date = now.toLocalDate().plusDays(d);
            if (!days.isEmpty() && !days.contains(date.getDayOfWeek())) continue;
            for (LocalTime t : times) {
                ZonedDateTime candidate = date.atTime(t).atZone(zone);
                if (candidate.isAfter(now)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public String nextEventName() {
        if (pool.isEmpty()) return "-";
        if (randomMode) return plugin.messages().raw("schedule.random-name");
        String id = pending != null ? pending : pool.get(rotationIndex % pool.size());
        return manager.displayName(id);
    }
}
