package nl.eventcore;

import nl.eventcore.event.EventCommand;
import nl.eventcore.event.EventManager;
import nl.eventcore.event.EventScheduler;
import nl.eventcore.event.dropper.DropperEvent;
import nl.eventcore.event.elytrarace.ElytraRaceEvent;
import nl.eventcore.event.hotpotato.HotPotatoEvent;
import nl.eventcore.event.lms.LastManStandingEvent;
import nl.eventcore.event.parkour.ParkourEvent;
import nl.eventcore.event.oneshot.OneShotEvent;
import nl.eventcore.event.spleef.SpleefEvent;
import nl.eventcore.event.tntrun.TntRunEvent;
import nl.eventcore.event.tnttag.TntTagEvent;
import nl.eventcore.event.arena.FloorArena;
import nl.eventcore.util.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class EventCorePlugin extends JavaPlugin {

    private Messages messages;
    private EventManager eventManager;
    private EventScheduler eventScheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Voegt nieuwe opties toe aan een bestaande config.yml zonder je locaties te overschrijven.
        getConfig().options().copyDefaults(true);
        saveConfig();

        messages = new Messages(this);
        messages.load();

        eventManager = new EventManager(this);
        getServer().getPluginManager().registerEvents(eventManager, this);

        // Nieuwe events registreer je hier: id + hoe je ze aanmaakt.
        eventManager.register("tnttag", () -> new TntTagEvent(this));
        eventManager.register("spleef", () -> new SpleefEvent(this));
        eventManager.register("tntrun", () -> new TntRunEvent(this));
        eventManager.register("elytrarace", () -> new ElytraRaceEvent(this));
        eventManager.register("lms", () -> new LastManStandingEvent(this));
        eventManager.register("oneshot", () -> new OneShotEvent(this));
        eventManager.register("hotpotato", () -> new HotPotatoEvent(this));
        eventManager.register("dropper", () -> new DropperEvent(this));
        eventManager.register("parkour", () -> new ParkourEvent(this));

        eventScheduler = new EventScheduler(this, eventManager);
        eventScheduler.start();

        PluginCommand cmd = getCommand("event");
        if (cmd != null) {
            EventCommand handler = new EventCommand(this, eventManager);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // Na een crash midden in een event: vloeren en inventarissen terugzetten.
        // Eén tick later, zodat werelden van andere plugins (bv. Multiverse) ook geladen zijn.
        getServer().getScheduler().runTask(this, this::recoverAfterCrash);

        getLogger().info("EventCore ingeschakeld met " + eventManager.ids().size() + " event(s).");
    }

    private void recoverAfterCrash() {
        File[] backups = new File(getDataFolder(), "recovery").listFiles((dir, name) -> name.startsWith("arena-") && name.endsWith(".txt"));
        if (backups != null) {
            for (File file : backups) FloorArena.recover(file, getLogger());
        }
        for (Player p : getServer().getOnlinePlayers()) eventManager.recoverSnapshot(p);
    }

    @Override
    public void onDisable() {
        if (eventScheduler != null) eventScheduler.stop();
        // Zorgt dat spelers hun inventaris terugkrijgen bij een reload/stop.
        if (eventManager != null) eventManager.stopActive();
    }

    /** Herlaadt config.yml, messages.yml en het schema. */
    public void reloadAll() {
        reloadConfig();
        messages.load();
        eventScheduler.start();
    }

    public Messages messages() { return messages; }
    public EventManager getEventManager() { return eventManager; }
    public EventScheduler getEventScheduler() { return eventScheduler; }
}
