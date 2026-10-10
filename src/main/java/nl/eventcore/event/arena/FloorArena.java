package nl.eventcore.event.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Een kubusvormig vloergebied (pos1-pos2) dat bij de start wordt onthouden en achteraf hersteld wordt.
 * Gebruikt door Spleef en TNT Run (later bv. Floor Is Lava of Block Party).
 *
 * Bij de start wordt ook een backup op schijf gezet. Valt de server midden in een event uit
 * (zonder nette stop), dan zet {@link #recover} de vloer bij de volgende start terug.
 */
public final class FloorArena {

    private record Entry(int x, int y, int z, BlockData data) {}

    private final World world;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final Set<Material> breakable;
    private final List<Entry> snapshot = new ArrayList<>();
    private final Random random = new Random();
    private final Logger log;
    private File backupFile;
    private BufferedWriter backupOut;

    public FloorArena(Location p1, Location p2, Set<Material> breakable, Logger log) {
        this.world = p1.getWorld();
        this.minX = Math.min(p1.getBlockX(), p2.getBlockX());
        this.maxX = Math.max(p1.getBlockX(), p2.getBlockX());
        this.minY = Math.min(p1.getBlockY(), p2.getBlockY());
        this.maxY = Math.max(p1.getBlockY(), p2.getBlockY());
        this.minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
        this.maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());
        this.breakable = breakable;
        this.log = log;
    }

    /** Aantal blokken in het gebied (voor de veiligheidslimiet). */
    public static long volume(Location p1, Location p2) {
        return (long) (Math.abs(p1.getBlockX() - p2.getBlockX()) + 1)
                * (Math.abs(p1.getBlockY() - p2.getBlockY()) + 1)
                * (Math.abs(p1.getBlockZ() - p2.getBlockZ()) + 1);
    }

    /**
     * Leest een lijst bloknamen uit de config. Naast een echte lijst ([TNT, SAND]) mag ook een losse
     * waarde (TNT of "TNT, SAND"); getStringList zou die stilletjes als lege lijst teruggeven.
     */
    public static List<String> readNames(ConfigurationSection cfg, String path) {
        if (cfg.isList(path)) return cfg.getStringList(path);
        String value = cfg.getString(path);
        if (value == null || value.isBlank()) return List.of();
        List<String> names = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) names.add(part.trim());
        }
        return names;
    }

    public static Set<Material> parseMaterials(List<String> names, Material fallback, Logger log, String label) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material mat = Material.matchMaterial(name);
            if (mat != null && mat.isBlock()) set.add(mat);
            else log.warning(label + ": onbekend blok '" + name + "'");
        }
        if (set.isEmpty() && fallback != null) set.add(fallback);
        return set;
    }

    public World world() { return world; }
    public int minY() { return minY; }

    public boolean isBreakable(Material m) {
        return breakable.contains(m);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Block b) {
        return b.getWorld().equals(world) && contains(b.getX(), b.getY(), b.getZ());
    }

    /**
     * Onthoudt alle blokken in het gebied, ook de lege plekken: komt er tijdens het spel iets
     * op een lege plek terecht (vallend zand, stromend water), dan wordt dat bij restore() weer lucht.
     * Schrijft daarnaast een backup naar schijf voor het geval de server crasht.
     */
    public void takeSnapshot(File backup) {
        snapshot.clear();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    snapshot.add(new Entry(x, y, z, world.getBlockAt(x, y, z).getBlockData()));
                }
            }
        }
        openBackup(backup);
    }

    /**
     * Onthoudt één extra blok dat buiten het gebied ligt, vlak voordat het verandert.
     * Zo wordt het bij restore() ook teruggezet.
     */
    public void remember(Block b) {
        if (!b.getWorld().equals(world) || contains(b)) return;
        Entry entry = new Entry(b.getX(), b.getY(), b.getZ(), b.getBlockData());
        snapshot.add(entry);
        appendBackup(entry);
    }

    /** De breekbare blokken uit de snapshot (bv. voor het afbrokkelen in Spleef). */
    public List<Block> breakableBlocks() {
        List<Block> list = new ArrayList<>();
        for (Entry e : snapshot) {
            if (breakable.contains(e.data().getMaterial())) list.add(world.getBlockAt(e.x(), e.y(), e.z()));
        }
        return list;
    }

    /**
     * Eén plek per kolom (bovenop het hoogste breekbare blok, met 2 blokken ruimte erboven),
     * geschud zodat spelers verspreid staan.
     */
    public List<Location> findSpots() {
        List<Location> spots = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = maxY; y >= minY; y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!breakable.contains(b.getType())) continue;
                    if (b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable()) {
                        spots.add(new Location(world, x + 0.5, y + 1, z + 0.5));
                    }
                    break;
                }
            }
        }
        Collections.shuffle(spots, random);
        return spots;
    }

    /** Zet alle onthouden blokken terug (alleen als ze veranderd zijn) en ruimt de backup op. */
    public void restore() {
        for (Entry e : snapshot) {
            Block b = world.getBlockAt(e.x(), e.y(), e.z());
            if (!b.getBlockData().equals(e.data())) {
                b.setBlockData(e.data(), false);
            }
        }
        snapshot.clear();
        closeBackup();
        if (backupFile != null && backupFile.exists() && !backupFile.delete()) {
            log.warning("Kon vloerbackup niet verwijderen: " + backupFile.getName());
        }
        backupFile = null;
    }

    // ------------------------------------------------------------------
    // Backup op schijf (tegen crashes)
    // Formaat: regel 1 = wereldnaam, daarna per blok "x y z blockdata".
    // Schrijven gebeurt bewust synchroon: de backup moet klaar zijn voordat er een blok verandert,
    // en mag pas weg als restore() klaar is. Het gebied is begrensd door max-region-volume.
    // ------------------------------------------------------------------

    private void openBackup(File file) {
        closeBackup();
        backupFile = file;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                throw new IOException("Kon map " + dir + " niet aanmaken");
            }
            backupOut = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
            backupOut.write(world.getName());
            backupOut.newLine();
            for (Entry e : snapshot) writeEntry(e);
            backupOut.flush();
        } catch (IOException ex) {
            log.log(Level.SEVERE, "Kon vloerbackup niet schrijven (" + file.getName()
                    + "); bij een crash wordt de vloer niet hersteld.", ex);
            closeBackup();
        }
    }

    private void appendBackup(Entry e) {
        if (backupOut == null) return;
        try {
            writeEntry(e);
            backupOut.flush();
        } catch (IOException ex) {
            log.log(Level.SEVERE, "Kon blok niet aan vloerbackup toevoegen", ex);
            closeBackup();
        }
    }

    private void writeEntry(Entry e) throws IOException {
        backupOut.write(e.x() + " " + e.y() + " " + e.z() + " " + e.data().getAsString());
        backupOut.newLine();
    }

    private void closeBackup() {
        if (backupOut == null) return;
        try {
            backupOut.close();
        } catch (IOException ex) {
            log.log(Level.WARNING, "Kon vloerbackup niet sluiten", ex);
        }
        backupOut = null;
    }

    /**
     * Zet een vloer terug uit een achtergebleven backup (na een crash) en verwijdert het bestand.
     * Bestaat de wereld (nog) niet, dan blijft het bestand staan voor een volgende poging.
     */
    public static void recover(File file, Logger log) {
        int restored = 0;
        try (BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String worldName = in.readLine();
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) {
                log.warning("Vloerbackup " + file.getName() + ": wereld '" + worldName + "' niet geladen, later opnieuw.");
                return;
            }
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.split(" ", 4);
                if (parts.length < 4) continue;
                try {
                    BlockData data = Bukkit.createBlockData(parts[3]);
                    Block b = world.getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                    if (!b.getBlockData().equals(data)) {
                        b.setBlockData(data, false);
                        restored++;
                    }
                } catch (IllegalArgumentException ex) {   // ook NumberFormatException
                    log.warning("Vloerbackup " + file.getName() + ": ongeldige regel overgeslagen: " + line);
                }
            }
        } catch (IOException ex) {
            log.log(Level.SEVERE, "Kon vloerbackup " + file.getName() + " niet lezen", ex);
            return;
        }
        if (!file.delete()) log.warning("Kon vloerbackup niet verwijderen: " + file.getName());
        log.info("Vloer hersteld na onverwacht afsluiten (" + file.getName() + ", " + restored + " blokken).");
    }
}
