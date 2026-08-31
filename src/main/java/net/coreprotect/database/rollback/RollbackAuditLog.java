package net.coreprotect.database.rollback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.model.action.LookupActions;
import net.coreprotect.utility.JsonWriter;

/**
 * Append-only JSON-lines audit trail of every real (non-preview) rollback/restore, so admins have a
 * durable, reviewable record of what a build/area restore actually did (who/when/where/filters/counts).
 */
public class RollbackAuditLog {
    private static final String FILENAME = "rollback-audit.jsonl";

    public static void record(CommandSender user, Location location, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, String timeString, Integer chunkCount, Double seconds, Integer itemCount, Integer blockCount, Integer entityCount, int rollbackType, Integer[] radius) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("timestamp", System.currentTimeMillis() / 1000L);
            record.put("initiator", user != null ? user.getName() : "#server");
            record.put("type", rollbackType == 0 ? "rollback" : "restore");
            record.put("world", location != null && location.getWorld() != null ? location.getWorld().getName() : null);

            if (radius != null) {
                if (radius.length > 7 && Integer.valueOf(1).equals(radius[7])) {
                    record.put("selection", "worldedit");
                }
                else if (radius[0] != null) {
                    record.put("radius", radius[0]);
                }
            }

            record.put("time", timeString);
            record.put("users", new ArrayList<>(checkUsers));

            if (!excludeUserList.isEmpty()) {
                record.put("excluded_users", new ArrayList<>(excludeUserList));
            }

            if (!actionList.isEmpty()) {
                List<String> actionNames = new ArrayList<>();
                for (Integer action : actionList) {
                    if (action != null && action >= 0) {
                        actionNames.add(LookupActions.getActionString(action));
                    }
                }
                record.put("actions", actionNames);
            }

            if (!restrictList.isEmpty()) {
                record.put("include", stringifyTargets(restrictList));
            }

            List<Object> userExcludes = new ArrayList<>();
            for (Map.Entry<Object, Boolean> entry : excludeList.entrySet()) {
                // exclude entries CoreProtect adds internally (e.g. fire/water/farmland for inventory
                // rollbacks) are marked TRUE - only log what the user actually asked to exclude.
                if (!Boolean.TRUE.equals(entry.getValue())) {
                    userExcludes.add(entry.getKey());
                }
            }
            if (!userExcludes.isEmpty()) {
                record.put("exclude", stringifyTargets(userExcludes));
            }

            record.put("blocks_modified", blockCount);
            record.put("items_modified", itemCount);
            record.put("entities_modified", entityCount);
            if (chunkCount != null && chunkCount > -1) {
                record.put("chunks_modified", chunkCount);
            }
            record.put("duration_seconds", seconds);

            append(JsonWriter.write(record));
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<String> stringifyTargets(List<Object> targets) {
        List<String> names = new ArrayList<>();
        for (Object target : targets) {
            if (target instanceof Material) {
                names.add(((Material) target).name().toLowerCase(Locale.ROOT));
            }
            else if (target instanceof EntityType) {
                names.add(((EntityType) target).name().toLowerCase(Locale.ROOT));
            }
            else if (target != null) {
                names.add(target.toString().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static synchronized void append(String json) {
        try {
            File folder = new File(ConfigHandler.path);
            if (!folder.exists()) {
                folder.mkdirs();
            }

            File file = new File(folder, FILENAME);
            try (FileOutputStream fout = new FileOutputStream(file, true); OutputStreamWriter out = new OutputStreamWriter(fout, StandardCharsets.UTF_8)) {
                out.write(json);
                out.write("\n");
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
