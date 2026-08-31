package net.coreprotect.database.migration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.command.CommandSender;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigFile;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.Database;
import net.coreprotect.language.Phrase;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.JsonWriter;

/**
 * Native SQLite <-> MySQL/MariaDB migration engine, replacing the proprietary/donation-gated
 * extension this fork doesn't ship. Reads the target's connection details fresh from config.yml on
 * disk (the admin edits config.yml to point at the new database but does NOT reload/restart first,
 * exactly like the documented workflow), copies every table in batches, and switches the live
 * connection over on success. Non-destructive: the source database is only ever read, never modified.
 */
public class DatabaseMigrator {

    // Runtime-only state (lock status, not historical data) - always recreated fresh on the target.
    private static final List<String> SKIP_TABLES = Arrays.asList("database_lock");
    private static final int BATCH_SIZE = 2000;

    private DatabaseMigrator() {
        throw new IllegalStateException("Utility class");
    }

    public static void migrate(CommandSender user, boolean targetIsMySQL, boolean mariaDbDriver) {
        long overallStart = System.currentTimeMillis();
        boolean sourceIsMySQL = Config.getGlobal().MYSQL;
        String sourcePrefix = ConfigHandler.prefix;

        Connection sourceConnection = null;
        Connection targetConnection = null;
        List<Map<String, Object>> tableReports = new ArrayList<>();

        try {
            TargetDatabaseSettings target = readTargetSettings(targetIsMySQL, mariaDbDriver);
            if (target == null) {
                Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.MIGRATION_CONFIG_NOT_READY));
                return;
            }

            sourceConnection = Database.getConnection(true, 0);
            if (sourceConnection == null) {
                Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.DATABASE_BUSY));
                return;
            }

            targetConnection = openTargetConnection(target);

            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.MIGRATION_STARTED));

            Database.createDatabaseTables(target.prefix, true, targetConnection, target.mysql, false);
            List<String> tables = new ArrayList<>(ConfigHandler.databaseTables);

            long totalRows = 0;
            for (String table : tables) {
                if (SKIP_TABLES.contains(table)) {
                    continue;
                }

                long rows = copyTable(sourceConnection, targetConnection, sourcePrefix, target.prefix, table);
                totalRows += rows;

                Map<String, Object> tableReport = new LinkedHashMap<>();
                tableReport.put("table", table);
                tableReport.put("rows", rows);
                tableReports.add(tableReport);

                Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.MIGRATION_PROGRESS, table.replace('_', ' '), NumberFormat.getInstance().format(rows)));
            }

            targetConnection.close();
            targetConnection = null;
            sourceConnection.close();
            sourceConnection = null;

            double totalSeconds = (System.currentTimeMillis() - overallStart) / 1000.0;
            writeReport(sourceIsMySQL, target, tableReports, totalRows, totalSeconds, true, null);

            // Switch over: config.yml already points at the target, so this loads it live.
            ConfigHandler.loadConfig();
            ConfigHandler.loadDatabase();

            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.MIGRATION_SUCCESS, NumberFormat.getInstance().format(totalRows), new BigDecimal(totalSeconds).setScale(1, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()));
        }
        catch (Exception e) {
            e.printStackTrace();
            Chat.sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.MIGRATION_FAILED));

            try {
                double totalSeconds = (System.currentTimeMillis() - overallStart) / 1000.0;
                writeReport(sourceIsMySQL, null, tableReports, -1, totalSeconds, false, e.toString());
            }
            catch (Exception reportError) {
                reportError.printStackTrace();
            }
        }
        finally {
            closeQuietly(sourceConnection);
            closeQuietly(targetConnection);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static TargetDatabaseSettings readTargetSettings(boolean targetIsMySQL, boolean mariaDbDriver) throws Exception {
        File configFile = new File(ConfigHandler.path, ConfigFile.CONFIG);
        if (!configFile.exists()) {
            return null;
        }

        Map<String, String> raw = new LinkedHashMap<>();
        ConfigFile.load(new FileInputStream(configFile), raw, false);

        boolean configuredMySQL = getBooleanValue(raw, "use-mysql", false);
        if (configuredMySQL != targetIsMySQL) {
            return null;
        }

        TargetDatabaseSettings settings = new TargetDatabaseSettings();
        settings.mysql = targetIsMySQL;

        if (targetIsMySQL) {
            settings.host = raw.getOrDefault("mysql-host", "127.0.0.1");
            settings.port = parseIntSafe(raw.get("mysql-port"), 3306);
            settings.database = raw.getOrDefault("mysql-database", "");
            settings.username = raw.getOrDefault("mysql-username", "");
            settings.password = raw.getOrDefault("mysql-password", "");
            settings.prefix = raw.getOrDefault("table-prefix", "co_");
            settings.mariaDbDriver = mariaDbDriver || "mariadb".equalsIgnoreCase(raw.get("mysql-driver"));

            if (settings.database.isEmpty() || settings.username.isEmpty() || settings.prefix.isEmpty()) {
                return null;
            }
        }
        else {
            // SQLite always uses the co_ prefix, regardless of table-prefix (see ConfigHandler.loadConfig).
            settings.prefix = "co_";
        }

        return settings;
    }

    private static boolean getBooleanValue(Map<String, String> raw, String key, boolean fallback) {
        String value = raw.get(key);
        return value == null ? fallback : value.startsWith("t");
    }

    private static int parseIntSafe(String value, int fallback) {
        if (value == null) {
            return fallback;
        }

        try {
            String digits = value.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? fallback : Integer.parseInt(digits);
        }
        catch (Exception e) {
            return fallback;
        }
    }

    private static Connection openTargetConnection(TargetDatabaseSettings target) throws Exception {
        Connection connection;

        if (target.mysql) {
            String driverClass = target.mariaDbDriver ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
            try {
                Class.forName(driverClass);
            }
            catch (Exception e) {
                if (target.mariaDbDriver) {
                    throw e;
                }
                Class.forName("com.mysql.jdbc.Driver");
            }

            // Option names differ between the two drivers; allowPublicKeyRetrieval and
            // rewriteBatchedStatements are Connector/J-only.
            String parameters = target.mariaDbDriver
                    ? "?useUnicode=true&characterEncoding=UTF-8&sslMode=" + (Config.getGlobal().ENABLE_SSL ? "trust" : "disable")
                    : "?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&useSSL=" + Config.getGlobal().ENABLE_SSL;
            String url = (target.mariaDbDriver ? "jdbc:mariadb://" : "jdbc:mysql://") + target.host + ":" + target.port + "/" + target.database + parameters;
            connection = DriverManager.getConnection(url, target.username, target.password);
        }
        else {
            connection = DriverManager.getConnection("jdbc:sqlite:" + ConfigHandler.path + ConfigHandler.sqlite);
            Database.applySQLitePragmas(connection);
        }

        connection.setAutoCommit(false);
        return connection;
    }

    private static Set<String> probeColumns(Connection connection, String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columns.add(metaData.getColumnName(i).toLowerCase(Locale.ROOT));
            }
        }

        return columns;
    }

    // SQLite's `entity`/`skull`/`user`/`username_log` tables use an explicit "id" column as their
    // primary key, while the MySQL/MariaDB schema uses "rowid" for the same role - and that value is
    // meaningfully referenced elsewhere (e.g. block.user, block.data for skulls), so it must be
    // preserved exactly rather than left to auto-increment. Every other table's rowid is a
    // never-referenced implementation detail that's fine to regenerate on the target.
    private static String mapColumnName(String sourceColumn, Set<String> targetColumns) {
        if (targetColumns.contains(sourceColumn)) {
            return sourceColumn;
        }
        if (sourceColumn.equals("id") && targetColumns.contains("rowid")) {
            return "rowid";
        }
        if (sourceColumn.equals("rowid") && targetColumns.contains("id")) {
            return "id";
        }

        return null;
    }

    private static long copyTable(Connection source, Connection target, String sourcePrefix, String targetPrefix, String table) throws Exception {
        String sourceTable = sourcePrefix + table;
        String targetTable = targetPrefix + table;
        Set<String> targetColumns = probeColumns(target, targetTable);

        long rowCount = 0;
        try (Statement sourceStatement = source.createStatement()) {
            try {
                sourceStatement.setFetchSize(500);
            }
            catch (Exception e) {
                // not every driver supports a custom fetch size - safe to ignore
            }

            try (ResultSet resultSet = sourceStatement.executeQuery("SELECT * FROM " + sourceTable)) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();

                List<Integer> sourceIndexes = new ArrayList<>();
                List<String> mappedColumns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    String mapped = mapColumnName(metaData.getColumnName(i).toLowerCase(Locale.ROOT), targetColumns);
                    if (mapped != null) {
                        sourceIndexes.add(i);
                        mappedColumns.add(mapped);
                    }
                }

                if (mappedColumns.isEmpty()) {
                    return 0;
                }

                StringBuilder insert = new StringBuilder("INSERT INTO ").append(targetTable).append(" (").append(String.join(",", mappedColumns)).append(") VALUES (");
                for (int i = 0; i < mappedColumns.size(); i++) {
                    insert.append(i == 0 ? "?" : ",?");
                }
                insert.append(")");

                try (PreparedStatement insertStatement = target.prepareStatement(insert.toString())) {
                    int pending = 0;
                    while (resultSet.next()) {
                        for (int i = 0; i < sourceIndexes.size(); i++) {
                            insertStatement.setObject(i + 1, resultSet.getObject(sourceIndexes.get(i)));
                        }
                        insertStatement.addBatch();
                        rowCount++;
                        pending++;

                        if (pending >= BATCH_SIZE) {
                            insertStatement.executeBatch();
                            target.commit();
                            pending = 0;
                        }
                    }

                    if (pending > 0) {
                        insertStatement.executeBatch();
                        target.commit();
                    }
                }
            }
        }

        return rowCount;
    }

    private static void writeReport(boolean sourceWasMySQL, TargetDatabaseSettings target, List<Map<String, Object>> tableReports, long totalRows, double totalSeconds, boolean success, String error) {
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("timestamp", System.currentTimeMillis() / 1000L);
            report.put("success", success);
            report.put("source", sourceWasMySQL ? "mysql" : "sqlite");
            report.put("target", target == null ? null : (target.mysql ? (target.mariaDbDriver ? "mariadb" : "mysql") : "sqlite"));
            report.put("tables", tableReports);
            report.put("total_rows", totalRows);
            report.put("duration_seconds", totalSeconds);
            if (error != null) {
                report.put("error", error);
            }

            File file = new File(ConfigHandler.path, "migration-report.json");
            try (FileOutputStream fout = new FileOutputStream(file, false); OutputStreamWriter out = new OutputStreamWriter(fout, StandardCharsets.UTF_8)) {
                out.write(JsonWriter.write(report));
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
