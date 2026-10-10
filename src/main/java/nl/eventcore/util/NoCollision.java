package nl.eventcore.util;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Zet botsen tussen spelers uit tijdens een event.
 *
 * Alleen setCollidable(false) is niet genoeg: de client voorspelt het duwen zelf. Daarom gaat de speler
 * ook in een scoreboard-team met collision-rule NEVER (op het scoreboard dat hij nu ziet, zodat het ook
 * werkt met plugins die eigen scoreboards gebruiken). Een speler kan maar in één team per scoreboard
 * zitten, dus het oude team wordt onthouden en na het event teruggezet.
 */
public final class NoCollision {

    private static final String TEAM = "eventcore_nocol";

    private NoCollision() {}

    /** Het team waar de speler nu in zit (of null), om later terug te zetten. */
    public static String currentTeam(Player p) {
        Team team = p.getScoreboard().getEntryTeam(p.getName());
        return team == null ? null : team.getName();
    }

    public static void apply(Player p) {
        Scoreboard board = p.getScoreboard();
        Team team = board.getTeam(TEAM);
        if (team == null) {
            team = board.registerNewTeam(TEAM);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            // Iedereen in het event zit in dit team: PvP (Last Man Standing, One Shot) moet gewoon blijven werken.
            team.setAllowFriendlyFire(true);
            team.setCanSeeFriendlyInvisibles(false);
        }
        team.addEntry(p.getName());
        p.setCollidable(false);
    }

    /** Haalt de speler uit het event-team en zet hem terug in zijn oude team (als dat nog bestaat). */
    public static void restore(Player p, String previousTeam, boolean collidable) {
        Scoreboard board = p.getScoreboard();
        Team team = board.getTeam(TEAM);
        if (team != null) team.removeEntry(p.getName());
        if (previousTeam != null) {
            Team old = board.getTeam(previousTeam);
            if (old != null) old.addEntry(p.getName());
        }
        p.setCollidable(collidable);
    }
}
