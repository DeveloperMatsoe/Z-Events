package nl.eventcore.util;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Bewaart de staat van een speler voor het event en zet die daarna terug.
 *
 * Naast het geheugen wordt de snapshot ook naar schijf geschreven (recovery/players/<uuid>.yml).
 * Valt de server midden in een event uit, dan krijgt de speler zijn spullen terug bij de volgende join.
 */
public final class PlayerSnapshot {

    private final ItemStack[] contents;
    private final Location location;
    private final GameMode gameMode;
    private final double health;
    private final double maxHealthBase;
    private final int food;
    private final float saturation;
    private final float exp;
    private final int level;
    private final Collection<PotionEffect> effects;
    private final boolean glowing;
    private final boolean allowFlight;
    private final boolean flying;
    private final boolean collidable;
    private final String team;
    private final int heldSlot;

    private PlayerSnapshot(Player p) {
        ItemStack[] src = p.getInventory().getContents();
        this.contents = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            contents[i] = src[i] == null ? null : src[i].clone();
        }
        this.location = p.getLocation().clone();
        this.gameMode = p.getGameMode();
        this.health = p.getHealth();
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        this.maxHealthBase = max != null ? max.getBaseValue() : 20.0;
        this.food = p.getFoodLevel();
        this.saturation = p.getSaturation();
        this.exp = p.getExp();
        this.level = p.getLevel();
        this.effects = p.getActivePotionEffects();
        this.glowing = p.isGlowing();
        this.allowFlight = p.getAllowFlight();
        this.flying = p.isFlying();
        this.collidable = p.isCollidable();
        this.team = NoCollision.currentTeam(p);
        this.heldSlot = p.getInventory().getHeldItemSlot();
    }

    private PlayerSnapshot(YamlConfiguration cfg) {
        this.contents = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(cfg.getString("contents", "")));
        World world = Bukkit.getWorld(cfg.getString("location.world", ""));
        this.location = world == null ? null : new Location(world,
                cfg.getDouble("location.x"), cfg.getDouble("location.y"), cfg.getDouble("location.z"),
                (float) cfg.getDouble("location.yaw"), (float) cfg.getDouble("location.pitch"));
        this.gameMode = GameMode.valueOf(cfg.getString("gamemode", GameMode.SURVIVAL.name()));
        this.health = cfg.getDouble("health", 20.0);
        this.maxHealthBase = cfg.getDouble("max-health-base", 20.0);
        this.food = cfg.getInt("food", 20);
        this.saturation = (float) cfg.getDouble("saturation", 5.0);
        this.exp = (float) cfg.getDouble("exp", 0.0);
        this.level = cfg.getInt("level", 0);
        List<PotionEffect> loaded = new ArrayList<>();
        for (Object o : cfg.getList("effects", List.of())) {
            if (o instanceof PotionEffect effect) loaded.add(effect);
        }
        this.effects = loaded;
        this.glowing = cfg.getBoolean("glowing", false);
        this.allowFlight = cfg.getBoolean("allow-flight", false);
        this.flying = cfg.getBoolean("flying", false);
        this.collidable = cfg.getBoolean("collidable", true);
        this.team = cfg.getString("team");
        this.heldSlot = Math.clamp(cfg.getInt("held-slot", 0), 0, 8);
    }

    public static PlayerSnapshot of(Player p) {
        return new PlayerSnapshot(p);
    }

    // ---------- opslag op schijf (tegen crashes) ----------

    /** Het bestand waarin de snapshot van een speler tijdens een event staat. */
    public static File file(File dataFolder, UUID uuid) {
        return new File(dataFolder, "recovery/players/" + uuid + ".yml");
    }

    public void save(File file) throws IOException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("contents", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(contents)));
        // Los opgeslagen i.p.v. als Location: die laadt niet als de wereld er (nog) niet is.
        cfg.set("location.world", location.getWorld().getName());
        cfg.set("location.x", location.getX());
        cfg.set("location.y", location.getY());
        cfg.set("location.z", location.getZ());
        cfg.set("location.yaw", location.getYaw());
        cfg.set("location.pitch", location.getPitch());
        cfg.set("gamemode", gameMode.name());
        cfg.set("health", health);
        cfg.set("max-health-base", maxHealthBase);
        cfg.set("food", food);
        cfg.set("saturation", saturation);
        cfg.set("exp", exp);
        cfg.set("level", level);
        cfg.set("effects", new ArrayList<>(effects));
        cfg.set("glowing", glowing);
        cfg.set("allow-flight", allowFlight);
        cfg.set("flying", flying);
        cfg.set("collidable", collidable);
        cfg.set("team", team);
        cfg.set("held-slot", heldSlot);
        cfg.save(file);
    }

    /**
     * Leest een snapshot van schijf.
     * @throws IllegalArgumentException als het bestand beschadigd is (bv. onbekende gamemode of items)
     */
    public static PlayerSnapshot load(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(file);
        return new PlayerSnapshot(cfg);
    }

    // ---------- toepassen ----------

    /** Maakt de speler "schoon" voor gebruik in een event. */
    public static void prepare(Player p) {
        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setGlowing(false);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExp(0f);
        p.setLevel(0);
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max != null ? max.getValue() : 20.0);
        NoCollision.apply(p);
    }

    public void restore(Player p) {
        p.getInventory().clear();
        p.getInventory().setContents(contents);
        p.getInventory().setHeldItemSlot(heldSlot);
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        p.addPotionEffects(effects);
        p.setGameMode(gameMode);
        p.setAllowFlight(allowFlight);
        p.setFlying(allowFlight && flying);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        // Eerst de maximale levens terug (bv. na One Shot), daarna pas de levens zelf.
        if (max != null) max.setBaseValue(maxHealthBase);
        double cap = max != null ? max.getValue() : 20.0;
        p.setHealth(Math.min(health, cap));
        p.setFoodLevel(food);
        p.setSaturation(saturation);
        p.setExp(exp);
        p.setLevel(level);
        p.setGlowing(glowing);
        p.setFireTicks(0);
        NoCollision.restore(p, team, collidable);
        // Na een crash kan de wereld van de oude locatie ontbreken; dan blijft de speler staan waar hij is.
        if (location != null) p.teleport(location);
    }
}
