# Database Migration (SQLite ↔ MySQL/MariaDB)

CoreProtect can migrate all of your data between SQLite and MySQL/MariaDB, in either direction,
using a built-in, console-only command - no add-ons or donation key required on this fork.

## Overview

The `/co migrate-db` command transfers all CoreProtect data (blocks, containers, items, chat,
commands, sessions, signs, skulls, users, and everything else) from whichever database you're
currently using to a different database type. This is useful when:

* Starting with SQLite and wanting to move to MySQL/MariaDB for better performance at scale
* Moving from MySQL/MariaDB back to SQLite for a simpler setup
* Switching between database servers

## Command Usage

| Command | Parameters | Description |
| --- | --- | --- |
| `/co migrate-db` | `<sqlite\|mysql\|mariadb>` | Migrate to the specified database type |

**Examples:**

* `/co migrate-db mysql` - Migrate to MySQL (or a MariaDB server, using the MySQL driver)
* `/co migrate-db mariadb` - Migrate to MariaDB using the native MariaDB driver
* `/co migrate-db sqlite` - Migrate back to SQLite

> **Console only:** this command can only be run from the server console, not in-game.

---

## Migration Process

### Step 1: Point config.yml at the new database

1. Keep your server running on your **current** database - don't touch it yet.
2. Edit `config.yml` so it describes the **new** (target) database: set `use-mysql` correctly, and
   fill in `mysql-host`/`mysql-port`/`mysql-database`/`mysql-username`/`mysql-password`/`table-prefix`
   (only needed when migrating *to* MySQL/MariaDB). Set `mysql-driver: mariadb` if you want the
   native MariaDB driver rather than the default MySQL driver (a MariaDB server also works fine with
   the default driver, since it speaks the same protocol).
3. **Do not restart the server or run `/co reload` after editing the file.** The migration command
   reads this file straight off disk for the target's connection details, while continuing to use
   your currently-connected database as the source - reloading first would make both point at the
   same (new) database, with nothing left to migrate from.
4. Make sure the target database (or an empty SQLite file location) exists and is reachable.

### Step 2: Run the migration

From the server console:

```
co migrate-db <sqlite|mysql|mariadb>
```

While it runs:

* The consumer (write queue) is paused so the source data doesn't change mid-copy.
* Every table is copied in batches, with a line printed to console as each table finishes.
* Your original database is only ever **read**, never modified.

### Step 3: Automatic switchover

Once every table has copied successfully, CoreProtect automatically reloads `config.yml` and
switches to the new database - that's what "don't reload first" in Step 1 was protecting. You'll see
a completion message in console with the total row count and duration. At that point:

1. Verify `/co status` and a `/co lookup` show data from the new database.
2. Once you're confident everything looks right, you can delete the old database (file or schema).

---

## Important Considerations

### Migration Safety

* **Non-destructive:** the migration only reads from the source database; it is never modified or
  deleted.
* **Interrupted migration:** if the server is stopped or the migration fails partway, the switchover
  never happens - CoreProtect keeps using the original database untouched. Delete the partially-written
  target database/tables before trying again.
* **Row IDs:** meaningful ID mappings (e.g. player IDs, world IDs, material IDs, skull/entity records)
  are preserved exactly. Purely internal row IDs that nothing else references are regenerated on the
  target, which is expected and harmless.

### Performance & Requirements

* Large databases (millions of rows) can take a while - the copy is batched (2,000 rows per batch)
  and committed incrementally, but it's still bounded by disk/network I/O on both ends.
* The migration is single-threaded per table; expect it to take roughly as long as a full table scan
  plus insert of your data.

### Restrictions & Limitations

* Console only - it cannot be run from in-game chat.
* You can't migrate to the type you're already using (e.g. `mysql` → `mysql`); if you only want to
  switch from the MySQL driver to the native MariaDB driver against the same server, just set
  `mysql-driver: mariadb` and reload/restart - no migration needed, since the schema is identical.

### A record of every migration

Every migration run (successful or not) writes a JSON summary to `migration-report.json` in your
CoreProtect folder - source/target type, per-table row counts, total duration, and the error message
if it failed.

---

## Troubleshooting

**"Update config.yml to point at the new database first" message:**

The command re-reads `config.yml` from disk and expects it to already describe the target you asked
for (e.g. running `/co migrate-db mysql` expects `use-mysql: true` with valid connection details
already saved in the file). Edit the file, save it, and run the command again without reloading.

**"The database is already using that type" message:**

You're trying to migrate to the same type you're currently connected with. Nothing to do.

**Migration failed partway:**

Check the console for the underlying exception (most often a connection problem, or a target database
that doesn't exist yet). Your original database is unaffected. Delete whatever the target migration
started writing, fix the underlying issue, and re-run the command.
