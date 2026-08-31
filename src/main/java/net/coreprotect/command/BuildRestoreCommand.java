package net.coreprotect.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import net.coreprotect.config.Config;

/**
 * /co buildrestore u:<user> t:<time> r:<radius> a:<action> i:<include> e:<exclude> [#hashtags]
 *
 * A thin, friendlier-by-default wrapper around /co rollback for "put this whole build/area back how it
 * was N days ago": it applies a much larger default radius than a targeted single-griefer rollback
 * would (buildrestore-radius, not default-radius), and - just like a plain rollback run without an a:
 * filter - covers every action type (blocks, containers, signs, items, entity kills) for any player in
 * one pass. All of rollback's u:/t:/r:/a:/i:/e:/#preview/#verbose/#silent parameters keep working
 * exactly as documented; this only changes what happens when r: is left unspecified.
 */
public class BuildRestoreCommand {

    private BuildRestoreCommand() {
        throw new IllegalStateException("Command class");
    }

    protected static void runCommand(CommandSender player, Command command, boolean permission, String[] args, Location argLocation, long forceStart, long forceEnd) {
        RollbackRestoreCommand.runCommand(player, command, permission, prepareArgs(args), argLocation, forceStart, forceEnd);
    }

    private static String[] prepareArgs(String[] args) {
        List<String> prepared = new ArrayList<>(args.length + 1);
        prepared.add("rollback"); // "restore" whole build/area to an earlier state = the rollback direction

        boolean hasRadius = false;
        for (int i = 1; i < args.length; i++) {
            String argument = args[i].trim().toLowerCase(Locale.ROOT);
            if (argument.startsWith("r:") || argument.startsWith("radius:")) {
                hasRadius = true;
            }
            prepared.add(args[i]);
        }

        if (!hasRadius) {
            prepared.add("r:" + Config.getGlobal().BUILDRESTORE_RADIUS);
        }

        return prepared.toArray(new String[0]);
    }
}
