package net.coreprotect.command;

import java.util.Locale;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.database.migration.DatabaseMigrator;
import net.coreprotect.language.Phrase;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;

/**
 * /co migrate-db <sqlite|mysql|mariadb> - console-only, native SQLite <-> MySQL/MariaDB migration.
 * Extends Consumer (like PurgeCommand) purely to reach the protected Consumer.pausedSuccess flag.
 */
public class MigrateDbCommand extends Consumer {

    private MigrateDbCommand() {
        throw new IllegalStateException("Command class");
    }

    protected static void runCommand(final CommandSender user, boolean permission, String[] args) {
        if (!permission) {
            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.NO_PERMISSION));
            return;
        }
        if (!(user instanceof ConsoleCommandSender)) {
            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.COMMAND_CONSOLE));
            return;
        }
        if (ConfigHandler.converterRunning || ConfigHandler.migrationRunning) {
            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.UPGRADE_IN_PROGRESS));
            return;
        }
        if (ConfigHandler.purgeRunning) {
            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.PURGE_IN_PROGRESS));
            return;
        }
        if (args.length != 2) {
            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.MISSING_PARAMETERS, Color.WHITE, "/co migrate-db <sqlite|mysql|mariadb>"));
            return;
        }

        String targetArg = args[1].trim().toLowerCase(Locale.ROOT);
        if (!targetArg.equals("sqlite") && !targetArg.equals("mysql") && !targetArg.equals("mariadb")) {
            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.MISSING_PARAMETERS, Color.WHITE, "/co migrate-db <sqlite|mysql|mariadb>"));
            return;
        }

        final boolean targetIsMySQL = !targetArg.equals("sqlite");
        final boolean mariaDbDriver = targetArg.equals("mariadb");
        if (targetIsMySQL == Config.getGlobal().MYSQL) {
            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.MIGRATION_SAME_TYPE));
            return;
        }

        ConfigHandler.converterRunning = true;
        ConfigHandler.migrationRunning = true;

        class MigrationThread implements Runnable {
            @Override
            public void run() {
                try {
                    Consumer.isPaused = true;
                    long waitStart = System.currentTimeMillis();
                    while (!Consumer.pausedSuccess && (System.currentTimeMillis() - waitStart) < 10000) {
                        Thread.sleep(10);
                    }

                    DatabaseMigrator.migrate(user, targetIsMySQL, mariaDbDriver);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                finally {
                    Consumer.isPaused = false;
                    ConfigHandler.converterRunning = false;
                    ConfigHandler.migrationRunning = false;
                }
            }
        }

        Thread thread = new Thread(new MigrationThread());
        thread.start();
    }
}
