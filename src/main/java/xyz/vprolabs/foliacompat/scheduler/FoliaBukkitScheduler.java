package xyz.vprolabs.foliacompat.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import xyz.vprolabs.foliacompat.ErrorReporter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SuppressWarnings("deprecation")
public class FoliaBukkitScheduler implements BukkitScheduler {
    private final ExecutorService executorService = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r); t.setDaemon(true); return t;
    });
    private final FoliaSchedulerAsync asyncScheduler = new FoliaSchedulerAsync();

    @Override
    public BukkitTask runTask(Plugin plugin, Runnable task) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        ScheduledTask st = Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> {
            try { task.run(); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        });
        return new BukkitTaskImpl(TaskMapper.register(st, plugin, true), plugin, st, true);
    }
    @Override
    public void runTask(Plugin plugin, Consumer<BukkitTask> consumer) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (consumer == null) throw new IllegalArgumentException("consumer cannot be null");
        int id = TaskMapper.reserveNextId();
        ScheduledTask st = Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> {
            try { consumer.accept(new BukkitTaskImpl(id, plugin, scheduledTask, true)); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        });
        TaskMapper.registerWithId(id, st, plugin, true);
    }
    @Override
    public BukkitTask runTask(Plugin plugin, BukkitRunnable task) {
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        return runTask(plugin, (Runnable) task);
    }
    @Override
    public BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        long clampedDelay = Math.max(1, delay);
        ScheduledTask st = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> {
            try { task.run(); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        }, clampedDelay);
        return new BukkitTaskImpl(TaskMapper.register(st, plugin, true), plugin, st, true);
    }
    @Override
    public void runTaskLater(Plugin plugin, Consumer<BukkitTask> consumer, long delay) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (consumer == null) throw new IllegalArgumentException("consumer cannot be null");
        long clampedDelay = Math.max(1, delay);
        int id = TaskMapper.reserveNextId();
        ScheduledTask st = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> {
            try { consumer.accept(new BukkitTaskImpl(id, plugin, scheduledTask, true)); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        }, clampedDelay);
        TaskMapper.registerWithId(id, st, plugin, true);
    }
    @Override
    public BukkitTask runTaskLater(Plugin plugin, BukkitRunnable task, long delay) {
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        return runTaskLater(plugin, (Runnable) task, delay);
    }
    @Override
    public BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        long clampedDelay = Math.max(1, delay);
        long clampedPeriod = Math.max(1, period);
        ScheduledTask st = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> {
            try { task.run(); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        }, clampedDelay, clampedPeriod);
        return new BukkitTaskImpl(TaskMapper.register(st, plugin, true), plugin, st, true);
    }
    @Override
    public void runTaskTimer(Plugin plugin, Consumer<BukkitTask> consumer, long delay, long period) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (consumer == null) throw new IllegalArgumentException("consumer cannot be null");
        long clampedDelay = Math.max(1, delay);
        long clampedPeriod = Math.max(1, period);
        int id = TaskMapper.reserveNextId();
        ScheduledTask st = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> {
            try { consumer.accept(new BukkitTaskImpl(id, plugin, scheduledTask, true)); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        }, clampedDelay, clampedPeriod);
        TaskMapper.registerWithId(id, st, plugin, true);
    }
    @Override
    public BukkitTask runTaskTimer(Plugin plugin, BukkitRunnable task, long delay, long period) {
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        return runTaskTimer(plugin, (Runnable) task, delay, period);
    }
    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        return asyncScheduler.runTaskAsynchronously(plugin, task);
    }
    @Override
    public void runTaskAsynchronously(Plugin plugin, Consumer<BukkitTask> consumer) {
        asyncScheduler.runTaskAsynchronously(plugin, consumer);
    }
    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, BukkitRunnable task) {
        return asyncScheduler.runTaskAsynchronously(plugin, task);
    }
    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        return asyncScheduler.runTaskLaterAsynchronously(plugin, task, delay);
    }
    @Override
    public void runTaskLaterAsynchronously(Plugin plugin, Consumer<BukkitTask> consumer, long delay) {
        asyncScheduler.runTaskLaterAsynchronously(plugin, consumer, delay);
    }
    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, BukkitRunnable task, long delay) {
        return asyncScheduler.runTaskLaterAsynchronously(plugin, task, delay);
    }
    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        return asyncScheduler.runTaskTimerAsynchronously(plugin, task, delay, period);
    }
    @Override
    public void runTaskTimerAsynchronously(Plugin plugin, Consumer<BukkitTask> consumer, long delay, long period) {
        asyncScheduler.runTaskTimerAsynchronously(plugin, consumer, delay, period);
    }
    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return asyncScheduler.runTaskTimerAsynchronously(plugin, task, delay, period);
    }
    @Override
    public void cancelTask(int taskId) {
        ScheduledTask task = TaskMapper.remove(taskId);
        if (task != null) {
            try { task.cancel(); }
            catch (IllegalStateException e) { /* already cancelled */ }
        }
    }
    @Override
    public void cancelTasks(Plugin plugin) { TaskMapper.cancelAll(plugin); }
    @Override
    public boolean isCurrentlyRunning(int taskId) {
        try {
            ScheduledTask task = TaskMapper.get(taskId);
            if (task == null) return false;
            return task.getExecutionState() == ScheduledTask.ExecutionState.RUNNING;
        } catch (Throwable t) { return false; }
    }
    @Override
    public boolean isQueued(int taskId) {
        try {
            ScheduledTask task = TaskMapper.get(taskId);
            if (task == null) return false;
            ScheduledTask.ExecutionState state = task.getExecutionState();
            return state != ScheduledTask.ExecutionState.FINISHED
                && state != ScheduledTask.ExecutionState.CANCELLED;
        } catch (Throwable t) { return false; }
    }
    @Override
    public List<BukkitTask> getPendingTasks() {
        return TaskMapper.getTaskMap().entrySet().stream()
            .map(e -> new BukkitTaskImpl(e.getKey(), e.getValue().getOwningPlugin(), e.getValue(),
                TaskMapper.getSyncFlag(e.getKey())))
            .collect(Collectors.toList());
    }
    @Override
    public List<BukkitWorker> getActiveWorkers() { return Collections.emptyList(); }
    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> callable) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
        if (callable == null) throw new IllegalArgumentException("callable cannot be null");
        try {
            Class<?> tt = Class.forName("io.papermc.paper.threadedregions.TickThread");
            java.lang.reflect.Method isTick = tt.getMethod("isTickThread");
            if ((boolean) isTick.invoke(null)) {
                try { return CompletableFuture.completedFuture(callable.call()); }
                catch (Exception e) { ErrorReporter.report(plugin.getName(), e); return CompletableFuture.failedFuture(e); }
            }
        } catch (Exception ignored) {}
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> {
            try { future.complete(callable.call()); }
            catch (Exception e) {
                ErrorReporter.report(plugin.getName(), e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }
    @Override @SuppressWarnings("deprecation") public int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) { return runTaskLater(plugin, task, delay).getTaskId(); }
    @Override @SuppressWarnings("deprecation") public int scheduleSyncDelayedTask(Plugin plugin, Runnable task) { return runTask(plugin, task).getTaskId(); }
    @Override @SuppressWarnings("deprecation") public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task, long delay) { return scheduleSyncDelayedTask(plugin, (Runnable) task, delay); }
    @Override @SuppressWarnings("deprecation") public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task) { return scheduleSyncDelayedTask(plugin, (Runnable) task); }
    @Override @SuppressWarnings("deprecation") public int scheduleSyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) { return runTaskTimer(plugin, task, delay, period).getTaskId(); }
    @Override @SuppressWarnings("deprecation") public int scheduleSyncRepeatingTask(Plugin plugin, BukkitRunnable task, long delay, long period) { return scheduleSyncRepeatingTask(plugin, (Runnable) task, delay, period); }
    @Override @SuppressWarnings("deprecation") public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task, long delay) { return runTaskLaterAsynchronously(plugin, task, delay).getTaskId(); }
    @Override @SuppressWarnings("deprecation") public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task) { return runTaskAsynchronously(plugin, task).getTaskId(); }
    @SuppressWarnings("deprecation") public int scheduleAsyncDelayedTask(Plugin plugin, BukkitRunnable task, long delay) { return scheduleAsyncDelayedTask(plugin, (Runnable) task, delay); }
    @Override @SuppressWarnings("deprecation") public int scheduleAsyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) { return runTaskTimerAsynchronously(plugin, task, delay, period).getTaskId(); }
    @SuppressWarnings("deprecation") public int scheduleAsyncRepeatingTask(Plugin plugin, BukkitRunnable task, long delay, long period) { return scheduleAsyncRepeatingTask(plugin, (Runnable) task, delay, period); }
    @SuppressWarnings("deprecation") public ExecutorService getExecutorService() { return executorService; }
    public void shutdown() { executorService.shutdown(); }
    public Executor getMainThreadExecutor(Plugin plugin) {
        return task -> Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try { task.run(); } catch (Throwable t) { ErrorReporter.report(plugin.getName(), t); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        });
    }
}
