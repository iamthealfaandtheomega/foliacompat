package xyz.vprolabs.foliacompat.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public class BukkitTaskImpl implements BukkitTask {
    private final int taskId;
    private final Plugin owner;
    private final ScheduledTask scheduledTask;
    private final boolean sync;

    public BukkitTaskImpl(int taskId, Plugin owner, ScheduledTask scheduledTask, boolean sync) {
        if (owner == null) throw new IllegalArgumentException("owner cannot be null");
        if (scheduledTask == null) throw new IllegalArgumentException("scheduledTask cannot be null");
        this.taskId = taskId;
        this.owner = owner;
        this.scheduledTask = scheduledTask;
        this.sync = sync;
    }

    @Override
    public int getTaskId() { return taskId; }

    @Override
    public Plugin getOwner() { return owner; }

    @Override
    public boolean isSync() { return sync; }

    @Override
    public boolean isCancelled() {
        try {
            return scheduledTask.isCancelled();
        } catch (IllegalStateException e) {
            Bukkit.getLogger().fine("isCancelled() failed for task " + taskId + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public void cancel() {
        try {
            scheduledTask.cancel();
        } catch (IllegalStateException e) {
            Bukkit.getLogger().fine("cancel() failed for task " + taskId + ": " + e.getMessage());
        }
    }
}
