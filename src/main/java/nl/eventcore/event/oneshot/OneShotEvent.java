package nl.eventcore.event.oneshot;

import nl.eventcore.EventCorePlugin;
import nl.eventcore.event.lms.LastManStandingEvent;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * One Shot: Last Man Standing, maar iedereen heeft maar 1 hart (instelbaar).
 * Met one-hit-kill is elke klap of pijl van een andere speler meteen raak.
 *
 * Arena, lobby, spawn en kit stel je los in onder oneshot.* (zelfde opties als lms).
 */
public final class OneShotEvent extends LastManStandingEvent {

    public static final String ID = "oneshot";

    private double health;
    private boolean oneHitKill;
    private int arrowsPerKill;

    public OneShotEvent(EventCorePlugin plugin) {
        super(plugin, ID, "One Shot");
    }

    @Override
    protected void onOpen() {
        FileConfiguration cfg = plugin.getConfig();
        // hearts: 1 = 1 hart = 2 levenspunten. Halve harten mogen (0.5).
        health = Math.max(1.0, cfg.getDouble(ID + ".hearts", 1.0) * 2.0);
        oneHitKill = cfg.getBoolean(ID + ".one-hit-kill", true);
        arrowsPerKill = Math.max(0, cfg.getInt(ID + ".arrows-per-kill", 1));
        super.onOpen();
    }

    @Override
    protected void prepareFighter(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) max.setBaseValue(health);
        player.setHealth(Math.min(health, max != null ? max.getValue() : health));
    }

    @Override
    protected boolean isLethal(Player victim, EntityDamageEvent e) {
        // Alleen een echte klap of een pijl/trident is meteen raak; sneeuwballen, eieren en vishaken niet.
        if (oneHitKill && e instanceof EntityDamageByEntityEvent byEntity
                && (byEntity.getDamager() instanceof Player || byEntity.getDamager() instanceof AbstractArrow)) {
            Player attacker = attackerOf(byEntity.getDamager());
            if (attacker != null && attacker != victim) return true;
        }
        return super.isLethal(victim, e);
    }

    /** Pijl terug voor elke kill, zodat je de boog blijft gebruiken. */
    @Override
    protected void onKill(Player killer) {
        if (arrowsPerKill <= 0) return;
        killer.getInventory().addItem(new ItemStack(Material.ARROW, arrowsPerKill));
        killer.sendMessage(m("arrow-reward", "amount", arrowsPerKill));
    }
}
