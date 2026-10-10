package nl.eventcore.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Items maken en op een bepaalde plek in de inventaris zetten.
 * Gedeeld door /event give en de kits van events.
 *
 * Item: zoals bij /give, dus ook met componenten, bv. diamond_sword[enchantments={sharpness:5}]
 * Plek: helmet, chestplate, leggings, boots, offhand, mainhand, of inventory
 *       (= de eerstvolgende vrije plek; dat is ook de standaard als je geen plek opgeeft).
 */
public final class ItemGiver {

    private ItemGiver() {}

    private static final Map<String, EquipmentSlot> SLOTS = Map.ofEntries(
            Map.entry("helmet", EquipmentSlot.HEAD),
            Map.entry("head", EquipmentSlot.HEAD),
            Map.entry("chestplate", EquipmentSlot.CHEST),
            Map.entry("chest", EquipmentSlot.CHEST),
            Map.entry("leggings", EquipmentSlot.LEGS),
            Map.entry("legs", EquipmentSlot.LEGS),
            Map.entry("boots", EquipmentSlot.FEET),
            Map.entry("feet", EquipmentSlot.FEET),
            Map.entry("offhand", EquipmentSlot.OFF_HAND),
            Map.entry("mainhand", EquipmentSlot.HAND),
            Map.entry("hand", EquipmentSlot.HAND));

    /** Namen voor tab-aanvulling. */
    public static final List<String> SLOT_NAMES =
            List.of("inventory", "helmet", "chestplate", "leggings", "boots", "offhand", "mainhand");

    /** Meer dan een volle inventaris (36 vakjes) heeft geen zin. */
    private static final int MAX_STACKS = 36;

    /** Maakt een item uit tekst zoals bij /give. Leeg als het item niet bestaat of geen item is. */
    public static Optional<ItemStack> parseItem(String input, int amount) {
        try {
            ItemStack item = Bukkit.getItemFactory().createItemStack(input);
            if (item.getType().isAir()) return Optional.empty();
            item.setAmount(Math.clamp(amount, 1, item.getMaxStackSize() * MAX_STACKS));
            return Optional.of(item);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Geen plek of "inventory" = de eerstvolgende vrije plek. */
    private static boolean isInventory(String slot) {
        return slot == null || slot.isEmpty() || slot.equalsIgnoreCase("inventory");
    }

    /** Is dit een geldige plek? */
    public static boolean isValidSlot(String slot) {
        return isInventory(slot) || SLOTS.containsKey(slot.toLowerCase(Locale.ROOT));
    }

    /**
     * Zet het item op de gekozen plek (wat daar lag wordt vervangen).
     * Zonder plek gaat het erbij; past het niet, dan valt de rest bij de speler op de grond.
     */
    public static void give(Player player, ItemStack item, String slot) {
        PlayerInventory inv = player.getInventory();
        if (isInventory(slot)) {
            for (ItemStack rest : inv.addItem(item.clone()).values()) {
                // In gewone stacks laten vallen, nooit één item-entity met een te grote stack.
                int left = rest.getAmount();
                while (left > 0) {
                    ItemStack drop = rest.clone();
                    drop.setAmount(Math.min(left, rest.getMaxStackSize()));
                    left -= drop.getAmount();
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            return;
        }

        ItemStack placed = item.clone();
        placed.setAmount(Math.min(placed.getAmount(), placed.getMaxStackSize()));
        inv.setItem(SLOTS.get(slot.toLowerCase(Locale.ROOT)), placed);
    }

    /** Itemnamen voor tab-aanvulling (zonder legacy-items). */
    public static List<String> itemNames(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isItem() || m.isAir() || m.name().startsWith("LEGACY_")) continue;
            String name = m.getKey().getKey();
            if (name.startsWith(p)) out.add(name);
        }
        return out;
    }

    /** Korte naam voor berichten, bv. "diamond_sword". */
    public static String name(ItemStack item) {
        return item.getType().getKey().getKey();
    }
}
