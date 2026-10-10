package nl.eventcore.event;

import net.kyori.adventure.text.Component;
import nl.eventcore.EventCorePlugin;
import nl.eventcore.util.ItemGiver;
import nl.eventcore.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class EventCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "eventcore.admin";
    private static final String PLAY = "eventcore.play";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE d MMMM 'om' HH:mm", Locale.forLanguageTag("nl"));

    private final EventCorePlugin plugin;
    private final EventManager manager;

    public EventCommand(EventCorePlugin plugin, EventManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private Messages m() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (!sender.hasPermission(ADMIN)) return noPerm(sender);
                if (args.length < 2) {
                    sender.sendMessage(m().prefixed("command.start-usage"));
                    return true;
                }
                sender.sendMessage(manager.start(args[1]).message());
            }
            case "stop" -> {
                if (!sender.hasPermission(ADMIN)) return noPerm(sender);
                sender.sendMessage(manager.stop(args.length >= 2 ? args[1] : null).message());
            }
            case "reload" -> {
                if (!sender.hasPermission(ADMIN)) return noPerm(sender);
                plugin.reloadAll();
                sender.sendMessage(m().prefixed("general.reloaded"));
            }
            case "join" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(m().prefixed("general.players-only"));
                    return true;
                }
                if (!p.hasPermission(PLAY)) return noPerm(sender);
                GameEvent active = manager.active().orElse(null);
                if (active == null) {
                    p.sendMessage(m().prefixed("event.none-to-join"));
                    return true;
                }
                if (active.isParticipant(p)) {
                    p.sendMessage(m().prefixed("event.already-joined"));
                    return true;
                }
                if (!active.join(p)) p.sendMessage(m().prefixed("event.cannot-join"));
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) return true;
                GameEvent active = manager.active().orElse(null);
                if (active == null || !active.isParticipant(p)) {
                    p.sendMessage(m().prefixed("event.not-in-event"));
                    return true;
                }
                active.leave(p);
                p.sendMessage(m().prefixed("event.left"));
            }
            case "list" -> {
                sender.sendMessage(m().prefixed("command.list", "events", String.join(", ", manager.ids())));
                manager.active().ifPresent(e -> sender.sendMessage(m().prefixed("command.list-active",
                        "event", e.getDisplayName(), "state", e.getState(), "count", e.getPlayerCount())));
            }
            case "next" -> {
                Optional<ZonedDateTime> next = plugin.getEventScheduler().nextRun();
                if (next.isEmpty()) {
                    sender.sendMessage(m().prefixed("command.next-none"));
                } else {
                    sender.sendMessage(m().prefixed("command.next",
                            "event", plugin.getEventScheduler().nextEventName(),
                            "time", TIME_FORMAT.format(next.get())));
                }
            }
            case "setup" -> {
                if (!sender.hasPermission(ADMIN)) return noPerm(sender);
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(m().prefixed("general.players-only"));
                    return true;
                }
                if (args.length < 3) {
                    p.sendMessage(m().prefixed("command.setup-usage"));
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                String key = args[2].toLowerCase(Locale.ROOT);
                if (!manager.ids().contains(id)) {
                    p.sendMessage(m().prefixed("event.unknown", "event", id));
                    return true;
                }
                List<String> keys = manager.setupKeys(id);
                if (!keys.contains(key)) {
                    p.sendMessage(m().prefixed("command.setup-keys", "event", id, "keys", String.join(", ", keys)));
                    return true;
                }
                // Sommige opties regelt het event zelf (bv. checkpoints toevoegen/verwijderen).
                if (manager.handleSetup(id, p, key, Arrays.copyOfRange(args, 3, args.length))) return true;

                Location loc;
                boolean isBlock = key.startsWith("pos");
                if (isBlock) {
                    // pos1/pos2: het blok waar je naar kijkt, anders het blok onder je voeten.
                    Block target = p.getTargetBlockExact(20);
                    Block block = target != null ? target : p.getLocation().getBlock().getRelative(BlockFace.DOWN);
                    loc = block.getLocation();
                } else {
                    loc = p.getLocation();
                }
                plugin.getConfig().set(id + "." + key, loc);
                plugin.saveConfig();

                p.sendMessage(m().prefixed("command.setup-done", "key", key, "event", id));
                if (isBlock) {
                    p.sendMessage(m().prefixed("command.setup-block",
                            "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ()));
                }
            }
            case "give" -> {
                if (!sender.hasPermission(ADMIN)) return noPerm(sender);
                give(sender, args);
            }
            default -> help(sender);
        }
        return true;
    }

    /** /event give <speler|all> <item> [plek] [aantal] */
    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(m().prefixed("command.give-usage"));
            return;
        }
        String slot = args.length >= 4 ? args[3] : null;
        if (!ItemGiver.isValidSlot(slot)) {
            sender.sendMessage(m().prefixed("command.give-unknown-slot", "slot", args[3]));
            return;
        }
        int amount = 1;
        if (args.length >= 5) {
            try {
                amount = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage(m().prefixed("command.give-usage"));
                return;
            }
        }
        Optional<ItemStack> item = ItemGiver.parseItem(args[2], amount);
        if (item.isEmpty()) {
            sender.sendMessage(m().prefixed("command.give-unknown-item", "item", args[2]));
            return;
        }

        List<Player> targets;
        if (args[1].equalsIgnoreCase("all")) {
            GameEvent active = manager.active().orElse(null);
            if (active == null) {
                sender.sendMessage(m().prefixed("event.not-running"));
                return;
            }
            targets = active.giveTargets();
        } else {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(m().prefixed("command.give-no-player", "player", args[1]));
                return;
            }
            targets = List.of(target);
        }

        String name = ItemGiver.name(item.get());
        for (Player p : targets) {
            ItemGiver.give(p, item.get(), slot);
            p.sendMessage(m().prefixed("command.give-received", "item", name, "amount", item.get().getAmount()));
        }
        sender.sendMessage(m().prefixed("command.give-done", "item", name,
                "amount", item.get().getAmount(), "count", targets.size()));
    }

    private void help(CommandSender s) {
        s.sendMessage(m().prefixed("command.help-header"));
        for (Component line : m().list("help.player")) s.sendMessage(line);
        if (s.hasPermission(ADMIN)) {
            for (Component line : m().list("help.admin")) s.sendMessage(line);
        }
    }

    private boolean noPerm(CommandSender s) {
        s.sendMessage(m().prefixed("general.no-permission"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            out.addAll(List.of("join", "leave", "list", "next"));
            if (admin) out.addAll(List.of("start", "stop", "setup", "give", "reload"));
        } else if (args[0].equalsIgnoreCase("give")) {
            if (!admin) return out;
            switch (args.length) {
                case 2 -> {
                    out.add("all");
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case 3 -> {
                    // Ruim 1000 items: pas aanvullen vanaf de eerste letter.
                    if (!args[2].isEmpty()) out.addAll(ItemGiver.itemNames(args[2]));
                }
                case 4 -> {
                    out.addAll(ItemGiver.SLOT_NAMES);
                }
                case 5 -> out.addAll(List.of("1", "16", "32", "64"));
                default -> { }
            }
        } else if (args.length == 2 && admin) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("start") || sub.equals("stop") || sub.equals("setup")) out.addAll(manager.ids());
        } else if (args.length == 3 && admin && args[0].equalsIgnoreCase("setup")) {
            out.addAll(manager.setupKeys(args[1]));
        } else if (args.length == 4 && admin && args[0].equalsIgnoreCase("setup")) {
            out.addAll(manager.setupArgs(args[1], args[2].toLowerCase(Locale.ROOT)));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
