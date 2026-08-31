package net.coreprotect.command;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import net.coreprotect.CoreProtect;
import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.consumer.ConsumerMetrics;
import net.coreprotect.consumer.process.Process;
import net.coreprotect.database.Database;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.coreprotect.patch.Patch;
import net.coreprotect.thread.NetworkHandler;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.SystemUtils;
import net.coreprotect.utility.VersionUtils;

public class StatusCommand {
    private static ConcurrentHashMap<String, Boolean> alert = new ConcurrentHashMap<>();

    protected static void runCommand(CommandSender player, boolean permission, String[] args) {
        if (!permission) {
            Chat.sendMessage(player, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.NO_PERMISSION));
            return;
        }

        class BasicThread implements Runnable {
            @Override
            public void run() {
                try {
                    CoreProtect instance = CoreProtect.getInstance();
                    PluginDescriptionFile pdfFile = instance.getDescription();

                    String versionCheck = "";
                    if (Config.getGlobal().CHECK_UPDATES) {
                        String latestVersion = NetworkHandler.latestVersion();
                        String latestEdgeVersion = NetworkHandler.latestEdgeVersion();
                        if (latestVersion != null) {
                            versionCheck = " (" + Phrase.build(Phrase.LATEST_VERSION, "v" + latestVersion) + ")";
                        }
                        else if (latestEdgeVersion != null && !VersionUtils.isCommunityEdition()) {
                            versionCheck = " (" + Phrase.build(Phrase.LATEST_VERSION, "v" + latestEdgeVersion) + ")";
                        }
                    }

                    Chat.sendMessage(player, Color.WHITE + "----- " + Color.DARK_AQUA + "CoreProtect" + (VersionUtils.isCommunityEdition() ? " " + ConfigHandler.COMMUNITY_EDITION : "") + Color.WHITE + " -----");
                    Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_VERSION, Color.WHITE, ConfigHandler.EDITION_NAME + " v" + pdfFile.getVersion() + ".") + versionCheck);

                    String donationKey = NetworkHandler.donationKey();
                    if (donationKey != null) {
                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_LICENSE, Color.WHITE, Phrase.build(Phrase.VALID_DONATION_KEY)) + " (" + donationKey + ")");
                    }
                    else {
                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_LICENSE, Color.WHITE, Phrase.build(Phrase.INVALID_DONATION_KEY)) + Color.GREY + Color.ITALIC + " (" + Phrase.build(Phrase.CHECK_CONFIG) + ")");
                    }

                    /*
                        Items processed (since server start)
                        Items processed (last 60 minutes)
                     */

                    // Using MySQL/SQLite (Database Size: 587MB)

                    String firstVersion = Patch.getFirstVersion();
                    if (firstVersion.length() > 0) {
                        firstVersion = " (" + Phrase.build(Phrase.FIRST_VERSION, firstVersion) + ")";
                    }
                    if (Config.getGlobal().MYSQL) {
                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_DATABASE, Color.WHITE, "MySQL") + firstVersion);
                    }
                    else {
                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_DATABASE, Color.WHITE, "SQLite") + firstVersion);
                    }

                    if (ConfigHandler.worldeditEnabled) {
                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_INTEGRATION, Color.WHITE, "WorldEdit", Selector.FIRST));
                    }
                    else {
                        Plugin worldEditPlugin = instance.getServer().getPluginManager().getPlugin("WorldEdit");
                        if (worldEditPlugin != null) {
                            Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_INTEGRATION, Color.WHITE, "WorldEdit", Selector.SECOND));
                            Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_INTEGRATION_VERSION, Color.WHITE, worldEditPlugin.getDescription().getVersion()));
                        }
                    }

                    try {
                        int consumerCount = 0;
                        int currentConsumerSize = Process.getCurrentConsumerSize();
                        if (currentConsumerSize == 0) {
                            consumerCount = Consumer.getConsumerSize(0) + Consumer.getConsumerSize(1);
                        }
                        else {
                            int consumerId = (Consumer.currentConsumer == 1) ? 1 : 0;
                            consumerCount = Consumer.getConsumerSize(consumerId) + currentConsumerSize;
                        }

                        if (consumerCount >= 1 && (player instanceof Player)) {
                            if (Config.getConfig(((Player) player).getWorld()).PLAYER_COMMANDS) {
                                consumerCount = consumerCount - 1;
                            }
                        }

                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_CONSUMER, Color.WHITE, String.format("%,d", consumerCount), (consumerCount == 1 ? Selector.FIRST : Selector.SECOND)));
                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_THROUGHPUT, Color.WHITE, String.format("%,.0f", ConsumerMetrics.getRowsPerSecond()), String.format("%.1f", ConsumerMetrics.getLastFlushMillis())));
                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_ROWS_WRITTEN, Color.WHITE, String.format("%,d", ConsumerMetrics.getTotalRows())));
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }

                    // Safe to query here: this whole block already runs on its own thread.
                    sendDatabaseMetrics(player);

                    long autoPurgeRowsPurged = ConfigHandler.autoPurgeRowsPurged.get();
                    Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_AUTO_PURGE, Color.WHITE, String.format("%,d", autoPurgeRowsPurged), (autoPurgeRowsPurged == 1 ? Selector.FIRST : Selector.SECOND)));

                    try {
                        String cpuInfo = "";
                        if (ConfigHandler.processorInfo != null) {
                            String modelName = ConfigHandler.processorInfo.getProcessorIdentifier().getName();
                            if (modelName.contains(" CPU")) {
                                String[] split = modelName.split(" CPU")[0].split(" ");
                                modelName = split[split.length - 1];
                            }
                            else if (modelName.contains(" Processor")) {
                                String[] split = modelName.split(" Processor")[0].split(" ");
                                modelName = split[split.length - 1];
                            }

                            String cpuSpeed = String.valueOf(ConfigHandler.processorInfo.getMaxFreq());
                            double speedVal = Long.valueOf(cpuSpeed) / 1000000000.0;

                            // Fix for Apple Silicon processors reporting 0 GHz
                            if (speedVal < 0.01 && SystemUtils.isAppleSilicon()) {
                                Double appleSiliconSpeed = SystemUtils.getAppleSiliconSpeed();
                                if (appleSiliconSpeed != null) {
                                    speedVal = appleSiliconSpeed;
                                }
                            }

                            cpuSpeed = String.format("%.2f", speedVal);
                            cpuInfo = "x" + Runtime.getRuntime().availableProcessors() + " " + cpuSpeed + "GHz " + modelName + ".";
                        }
                        else {
                            cpuInfo = "x" + Runtime.getRuntime().availableProcessors() + " " + Phrase.build(Phrase.CPU_CORES);
                        }

                        int mb = 1024 * 1024;
                        Runtime runtime = Runtime.getRuntime();
                        String usedRAM = String.format("%.2f", Double.valueOf((runtime.totalMemory() - runtime.freeMemory()) / mb) / 1000.0);
                        String totalRAM = String.format("%.2f", Double.valueOf(runtime.maxMemory() / mb) / 1000.0);
                        String systemInformation = Phrase.build(Phrase.RAM_STATS, usedRAM, totalRAM);
                        if (cpuInfo.length() > 0) {
                            systemInformation = cpuInfo + " (" + systemInformation + ")";
                        }

                        Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_SYSTEM, Color.WHITE, systemInformation));
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }

                    // Functions.sendMessage(player, Color.DARK_AQUA + "Website: " + Color.WHITE + "www.coreprotect.net/updates/");

                    // Functions.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.LINK_DISCORD, Color.WHITE + "www.coreprotect.net/discord/").replaceFirst(":", ":" + Color.WHITE));
                    Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.LINK_DISCORD, Color.WHITE, "www.coreprotect.net/discord/"));
                    Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.LINK_PATREON, Color.WHITE, "www.patreon.com/coreprotect/"));

                    if (player.isOp() && alert.get(player.getName()) == null) {
                        alert.put(player.getName(), true);

                        if (instance.getServer().getPluginManager().getPlugin("CoreEdit") == null) {
                            Thread.sleep(500);
                            /*
                            Functions.sendMessage(player, Color.WHITE + "----- " + Color.DARK_AQUA + "Recommended Plugin " + Color.WHITE + "-----");
                            Functions.sendMessage(player, Color.DARK_AQUA + "Notice: " + Color.WHITE + "Enjoy CoreProtect? Check out CoreEdit!");
                            Functions.sendMessage(player, Color.DARK_AQUA + "Download: " + Color.WHITE + "www.coreedit.net/download/");
                            */
                        }
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        Runnable runnable = new BasicThread();
        Thread thread = new Thread(runnable);
        thread.start();
    }

    /**
     * Reports database round-trip latency and on-disk size. Deliberately avoids COUNT(*), which
     * would scan hundreds of millions of rows on a busy server just to render a status line.
     */
    private static void sendDatabaseMetrics(CommandSender player) {
        try (Connection connection = Database.getConnection(false, 1000)) {
            if (connection == null) {
                return;
            }

            long latencyStart = System.nanoTime();
            try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                resultSet.next();
            }
            double latency = (System.nanoTime() - latencyStart) / 1000000.0;
            Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_DATABASE_LATENCY, Color.WHITE, String.format("%.1f", latency)));

            long size = getDatabaseSize(connection);
            if (size > 0) {
                Chat.sendMessage(player, Color.DARK_AQUA + Phrase.build(Phrase.STATUS_DATABASE_SIZE, Color.WHITE, formatBytes(size)));
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static long getDatabaseSize(Connection connection) {
        if (!Config.getGlobal().MYSQL) {
            long size = 0;
            File databaseFile = new File(ConfigHandler.path, ConfigHandler.sqlite);
            if (databaseFile.exists()) {
                size = databaseFile.length();
            }

            // In WAL mode a meaningful amount of data can be sitting in the -wal file.
            File walFile = new File(ConfigHandler.path, ConfigHandler.sqlite + "-wal");
            if (walFile.exists()) {
                size = size + walFile.length();
            }

            return size;
        }

        try (PreparedStatement statement = connection.prepareStatement("SELECT SUM(data_length + index_length) AS size FROM information_schema.TABLES WHERE table_schema = ?")) {
            statement.setString(1, ConfigHandler.database);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("size");
                }
            }
        }
        catch (Exception e) {
            // information_schema isn't always readable by the configured user
        }

        return 0;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1073741824L) {
            return String.format("%.1f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1048576L) {
            return String.format("%.1f MB", bytes / 1048576.0);
        }

        return String.format("%.1f KB", bytes / 1024.0);
    }
}
