package sh.okx.deathban.scheduler;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Cross-platform scheduler abstraction that transparently supports both Folia and standard
 * Bukkit/Spigot/Paper/Purpur servers from a single code path.
 *
 * <p>Folia uses a regionized threading model where {@link org.bukkit.Bukkit#getScheduler()} throws
 * {@link UnsupportedOperationException}. This class detects the platform at startup and delegates
 * to the correct scheduler implementation accordingly.</p>
 */
public final class SchedulerUtil {

    private static final boolean FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {
            folia = false;
        }
        FOLIA = folia;
    }

    private SchedulerUtil() {}

    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Run a task on the main thread (Bukkit/Spigot/Paper/Purpur) or the global region (Folia).
     * Safe for non-entity server operations like command dispatch.
     */
    public static void runSync(Plugin plugin, Runnable task) {
        if (FOLIA) {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, st -> task.run());
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run a task in the context of a specific player's region (Folia) or the main thread.
     * Required for per-player operations such as kicking a player on Folia.
     * On Folia, if the player retires (disconnects) before the task executes, it is silently dropped.
     */
    public static void runForPlayer(Plugin plugin, Player player, Runnable task) {
        if (FOLIA) {
            player.getScheduler().run(plugin, st -> task.run(), null);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run a task off the main thread (async).
     * On Folia uses {@code AsyncScheduler}; on standard servers uses {@code BukkitScheduler}.
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (FOLIA) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, st -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Schedule a repeating task on the global region (Folia) or the main thread.
     *
     * @param delayTicks  initial delay in server ticks before the first execution
     * @param periodTicks interval in server ticks between subsequent executions
     * @return a {@link CancelableTask} that can be used to stop the repeating task
     */
    public static CancelableTask runRepeating(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            var scheduled = plugin.getServer().getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, st -> task.run(), delayTicks, periodTicks);
            return scheduled::cancel;
        } else {
            BukkitTask t = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return t::cancel;
        }
    }

    @FunctionalInterface
    public interface CancelableTask {
        void cancel();
    }
}
