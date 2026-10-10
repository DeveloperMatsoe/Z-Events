package nl.eventcore.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Herkent items die een event zelf uitdeelt (reset-kleurstof, skip-ijzerstaaf, ...) aan een tag in de
 * item-data, niet aan het materiaal of de naam. Zo wordt een rode kleurstof uit /event give geen reset-item.
 */
public final class EventItems {

    private static final NamespacedKey KEY = NamespacedKey.fromString("eventcore:item");

    private EventItems() {}

    /** Markeert het item als event-item van een bepaald soort (bv. "reset", "skip"). */
    public static ItemStack tag(ItemStack item, String type) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, type));
        return item;
    }

    /** Is dit een event-item van dit soort? */
    public static boolean is(ItemStack item, String type) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return false;
        return type.equals(item.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING));
    }
}
