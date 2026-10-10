package nl.eventcore.event;

import nl.eventcore.EventCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;

/** Voert de beloningen uit config.yml uit (<event>.rewards.*). Gedeeld door alle events. */
public final class RewardService {

    private RewardService() {}

    public static void give(EventCorePlugin plugin, String eventId, int startedWith,
                            Player winner, List<Player> participants) {
        FileConfiguration cfg = plugin.getConfig();
        String base = eventId + ".rewards.";
        if (!cfg.getBoolean(base + "enabled", false)) return;
        if (startedWith < cfg.getInt(base + "min-players", 4)) return;

        List<String> winnerCmds = cfg.getStringList(base + "winner");
        List<String> participationCmds = cfg.getStringList(base + "participation");

        if (winner != null && !winnerCmds.isEmpty()) {
            run(winnerCmds, winner);
            winner.sendMessage(plugin.messages().prefixed("general.reward-winner"));
        }
        if (!participationCmds.isEmpty()) {
            for (Player p : participants) {
                run(participationCmds, p);
                p.sendMessage(plugin.messages().prefixed("general.reward-participation"));
            }
        }
    }

    /**
     * Beloningen per plek (<event>.rewards.places.1, .2, .3, ...) plus deelname.
     * ranking = de spelers op volgorde van hun plek (index 0 = plek 1).
     */
    public static void givePlaces(EventCorePlugin plugin, String eventId, int startedWith,
                                  List<Player> ranking, List<Player> participants) {
        FileConfiguration cfg = plugin.getConfig();
        String base = eventId + ".rewards.";
        if (!cfg.getBoolean(base + "enabled", false)) return;
        if (startedWith < cfg.getInt(base + "min-players", 4)) return;

        for (int i = 0; i < ranking.size(); i++) {
            int place = i + 1;
            List<String> cmds = cfg.getStringList(base + "places." + place);
            if (cmds.isEmpty()) continue;
            run(cmds, ranking.get(i));
            ranking.get(i).sendMessage(plugin.messages().prefixed("general.reward-place", "place", place));
        }

        List<String> participationCmds = cfg.getStringList(base + "participation");
        if (!participationCmds.isEmpty()) {
            for (Player p : participants) {
                run(participationCmds, p);
                p.sendMessage(plugin.messages().prefixed("general.reward-participation"));
            }
        }
    }

    private static void run(List<String> commands, Player player) {
        for (String raw : commands) {
            String cmd = raw.replace("%player%", player.getName()).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (cmd.isEmpty()) continue;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }
}
