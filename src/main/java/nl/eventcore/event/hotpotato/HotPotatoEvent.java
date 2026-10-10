package nl.eventcore.event.hotpotato;

import nl.eventcore.EventCorePlugin;
import nl.eventcore.event.tnttag.TntTagEvent;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Hot Potato: zoals TNT Tag, maar met één hete aardappel (instelbaar) en een willekeurige
 * rondetijd die niemand ziet. Je weet dus nooit wanneer hij ontploft.
 *
 * Lobby en spawn stel je los in: /event setup hotpotato lobby | spawn.
 */
public final class HotPotatoEvent extends TntTagEvent {

    public static final String ID = "hotpotato";

    private int holders;
    private int fuseMin;
    private int fuseMax;

    public HotPotatoEvent(EventCorePlugin plugin) {
        super(plugin, ID, "Hot Potato");
    }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        holders = Math.max(1, cfg.getInt(ID + ".holders", 1));
        fuseMin = Math.max(3, cfg.getInt(ID + ".fuse-min-seconds", 10));
        fuseMax = Math.max(fuseMin, cfg.getInt(ID + ".fuse-max-seconds", 35));
        super.onOpen();
    }

    @Override
    protected int taggerCount(int alive) {
        return holders;
    }

    /** Elke ronde een nieuwe, willekeurige tijd tussen fuse-min-seconds en fuse-max-seconds. */
    @Override
    protected int fuseLength() {
        return ThreadLocalRandom.current().nextInt(fuseMin, fuseMax + 1);
    }

    @Override
    protected boolean showFuseTime() {
        return false;
    }

    @Override
    protected ItemStack tagItem() {
        return new ItemStack(Material.BAKED_POTATO);
    }
}
