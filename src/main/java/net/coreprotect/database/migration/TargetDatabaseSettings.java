package net.coreprotect.database.migration;

/**
 * Connection details for a migrate-db target, read fresh from config.yml on disk (not from the live
 * Config singleton, which still reflects whatever database is currently active/connected).
 */
class TargetDatabaseSettings {
    boolean mysql;
    boolean mariaDbDriver;
    String host;
    int port;
    String database;
    String username;
    String password;
    String prefix;
}
