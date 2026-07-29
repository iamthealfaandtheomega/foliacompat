package xyz.vprolabs.foliacompat.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xyz.vprolabs.foliacompat.ErrorReporter;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class FoliaSchedulerAsync {
    public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        ScheduledTask st = Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> {
            try { task.run(); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        });
        return new BukkitTaskImpl(TaskMapper.register(st, plugin, false), plugin, st, false);
    }
    public void runTaskAsynchronously(Plugin plugin, Consumer<BukkitTask> consumer) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (consumer == null) throw new IllegalArgumentException("consumer cannot be null");
        int id = TaskMapper.reserveNextId();
        ScheduledTask st = Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> {
            try { consumer.accept(new BukkitTaskImpl(id, plugin, scheduledTask, false)); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        });
        TaskMapper.registerWithId(id, st, plugin, false);
    }
    public BukkitTask runTaskAsynchronously(Plugin plugin, BukkitRunnable task) {
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        return runTaskAsynchronously(plugin, (Runnable) task);
    }
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        long clampedDelay = Math.max(1, delay);
        ScheduledTask st = Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> {
            try { task.run(); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        }, clampedDelay * 50, TimeUnit.MILLISECONDS);
        return new BukkitTaskImpl(TaskMapper.register(st, plugin, false), plugin, st, false);
    }
    public void runTaskLaterAsynchronously(Plugin plugin, Consumer<BukkitTask> consumer, long delay) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (consumer == null) throw new IllegalArgumentException("consumer cannot be null");
        long clampedDelay = Math.max(1, delay);
        int id = TaskMapper.reserveNextId();
        ScheduledTask st = Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> {
            try { consumer.accept(new BukkitTaskImpl(id, plugin, scheduledTask, false)); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        }, clampedDelay * 50, TimeUnit.MILLISECONDS);
        TaskMapper.registerWithId(id, st, plugin, false);
    }
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, BukkitRunnable task, long delay) {
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        return runTaskLaterAsynchronously(plugin, (Runnable) task, delay);
    }
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        long clampedDelay = Math.max(1, delay);
        long clampedPeriod = Math.max(1, period);
        ScheduledTask st = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> {
            try { task.run(); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        }, clampedDelay * 50, clampedPeriod * 50, TimeUnit.MILLISECONDS);
        return new BukkitTaskImpl(TaskMapper.register(st, plugin, false), plugin, st, false);
    }
    public void runTaskTimerAsynchronously(Plugin plugin, Consumer<BukkitTask> consumer, long delay, long period) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (consumer == null) throw new IllegalArgumentException("consumer cannot be null");
        long clampedDelay = Math.max(1, delay);
        long clampedPeriod = Math.max(1, period);
        int id = TaskMapper.reserveNextId();
        ScheduledTask st = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> {
            try { consumer.accept(new BukkitTaskImpl(id, plugin, scheduledTask, false)); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        }, clampedDelay * 50, clampedPeriod * 50, TimeUnit.MILLISECONDS);
        TaskMapper.registerWithId(id, st, plugin, false);
    }
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, BukkitRunnable task, long delay, long period) {
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        return runTaskTimerAsynchronously(plugin, (Runnable) task, delay, period);
    }
}
