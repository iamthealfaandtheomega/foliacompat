package xyz.vprolabs.foliacompat.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class TaskMapper {
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, ScheduledTask> tasksById = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Plugin, CopyOnWriteArrayList<Integer>> tasksByPlugin = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> taskSyncFlags = new ConcurrentHashMap<>();

    private TaskMapper() {}

    public static int register(ScheduledTask task, Plugin plugin) {
        return register(task, plugin, true);
    }

    public static int reserveNextId() {
        return nextId.getAndIncrement();
    }

    public static void registerWithId(int id, ScheduledTask task, Plugin plugin, boolean sync) {
        tasksById.put(id, task);
        taskSyncFlags.put(id, sync);
        tasksByPlugin.computeIfAbsent(plugin, k -> new CopyOnWriteArrayList<>()).add(id);
    }

    public static int register(ScheduledTask task, Plugin plugin, boolean sync) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        int id = nextId.getAndIncrement();
        tasksById.put(id, task);
        taskSyncFlags.put(id, sync);
        tasksByPlugin.computeIfAbsent(plugin, k -> new CopyOnWriteArrayList<>()).add(id);
        return id;
    }

    public static boolean getSyncFlag(int id) {
        return taskSyncFlags.getOrDefault(id, true);
    }

    public static ScheduledTask get(int id) {
        return tasksById.get(id);
    }

    public static ScheduledTask remove(int id) {
        ScheduledTask task = tasksById.remove(id);
        taskSyncFlags.remove(id);
        if (task != null) {
            Plugin plugin = task.getOwningPlugin();
            if (plugin != null) {
                CopyOnWriteArrayList<Integer> ids = tasksByPlugin.get(plugin);
                if (ids != null) {
                    ids.remove(Integer.valueOf(id));
                    if (ids.isEmpty()) {
                        tasksByPlugin.remove(plugin, ids);
                    }
                }
            }
        }
        return task;
    }

    public static void cancelAll(Plugin plugin) {
        try {
            if (plugin == null) {
                for (ScheduledTask task : tasksById.values()) {
                    if (task != null) {
                        try { task.cancel(); }
                        catch (IllegalStateException e) {
                            Bukkit.getLogger().fine("Task already cancelled: " + e.getMessage());
                        }
                    }
                }
                tasksById.clear();
                taskSyncFlags.clear();
                tasksByPlugin.clear();
                return;
            }
            CopyOnWriteArrayList<Integer> ids = tasksByPlugin.remove(plugin);
            if (ids != null) {
                for (int id : ids) {
                    ScheduledTask task = tasksById.remove(id);
                    taskSyncFlags.remove(id);
                    if (task != null) {
                        try { task.cancel(); }
                        catch (IllegalStateException e) {
                            Bukkit.getLogger().fine("Task already cancelled: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (ConcurrentModificationException e) {
            Bukkit.getLogger().warning("Concurrent modification during cancelAll for plugin " + plugin + ": " + e.getMessage());
        }
    }

    public static Map<Integer, ScheduledTask> getTaskMap() {
        return tasksById;
    }
}
