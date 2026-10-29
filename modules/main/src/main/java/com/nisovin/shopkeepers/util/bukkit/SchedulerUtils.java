package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.api.util.ChunkCoords;
import com.nisovin.shopkeepers.util.java.Validate;

/**
 * Scheduler related utilities.
 * <p>
 * On Paper based servers, tasks are scheduled via Paper's region-aware schedulers: Global tasks run
 * on the global region thread, region tasks on the thread owning the targeted chunk, and entity
 * tasks on the thread owning the targeted entity. On servers with a single main thread (Paper,
 * Purpur), all of these run on the main thread. Otherwise, the Bukkit scheduler is used.
 * <p>
 * All synchronous tasks hold the {@link PluginLock} while they run. Tasks that were scheduled
 * before the last {@link #cancelAllTasks(Plugin)} are skipped when they would run.
 */
public final class SchedulerUtils {

	private static @Nullable SchedulerBackend backend;
	private static final AtomicInteger activeAsyncTasks = new AtomicInteger();
	// Incremented when all tasks are cancelled. Tasks scheduled before are skipped:
	private static volatile int taskGeneration = 0;

	private static SchedulerBackend getBackend() {
		SchedulerBackend backend = SchedulerUtils.backend;
		if (backend == null) {
			backend = hasRegionSchedulers() ? createPaperBackend() : new BukkitSchedulerBackend();
			SchedulerUtils.backend = backend;
		}
		return backend;
	}

	// Loaded reflectively: The Paper backend is excluded from the null analysis, which uses the
	// Spigot API.
	private static SchedulerBackend createPaperBackend() {
		try {
			Class<?> backendClass = Class.forName(
					"com.nisovin.shopkeepers.util.bukkit.PaperSchedulerBackend"
			);
			return (SchedulerBackend) backendClass.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not create the Paper scheduler backend!", e);
		}
	}

	private static boolean hasRegionSchedulers() {
		try {
			Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
			Bukkit.class.getMethod("getRegionScheduler");
			return true;
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			return false;
		}
	}

	/**
	 * Creates an {@link Executor} that executes tasks on the server's main thread using
	 * {@link #runOnMainThreadOrOmit(Plugin, Runnable)}.
	 * <p>
	 * If the thread registering the task is already a server thread, the task is run immediately.
	 * Otherwise, it is scheduled as a global task. If the plugin is not enabled at the time of task
	 * registration, the task is omitted.
	 * 
	 * @param plugin
	 *            the plugin
	 * @return the executor
	 */
	public static Executor createSyncExecutor(Plugin plugin) {
		return (runnable) -> runOnMainThreadOrOmit(plugin, runnable);
	}

	/**
	 * Creates an {@link Executor} that executes tasks using
	 * {@link #runAsyncTaskOrOmit(Plugin, Runnable)}.
	 * 
	 * @param plugin
	 *            the plugin
	 * @return the executor
	 */
	public static Executor createAsyncExecutor(Plugin plugin) {
		return (runnable) -> runAsyncTaskOrOmit(plugin, runnable);
	}

	public static int getActiveAsyncTasks(Plugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		return activeAsyncTasks.get();
	}

	private static void validatePluginTask(Plugin plugin, Runnable task) {
		Validate.notNull(plugin, "plugin is null");
		Validate.notNull(task, "task is null");
	}

	private static Runnable wrapSync(Plugin plugin, Runnable task) {
		int generation = taskGeneration;
		return () -> {
			if (generation != taskGeneration || !plugin.isEnabled()) return;
			PluginLock.run(task);
		};
	}

	// Not skipped by cancelAllTasks, e.g. for removing entities that were despawned during a
	// reload.
	private static Runnable wrapCleanup(Plugin plugin, Runnable task) {
		return () -> {
			if (!plugin.isEnabled()) return;
			PluginLock.run(task);
		};
	}

	private static @Nullable Runnable wrapRetired(Plugin plugin, @Nullable Runnable retired) {
		if (retired == null) return null;
		return wrapSync(plugin, retired);
	}

	private static Runnable wrapAsync(Plugin plugin, Runnable task) {
		int generation = taskGeneration;
		return () -> {
			if (generation != taskGeneration) return;
			activeAsyncTasks.incrementAndGet();
			try {
				task.run();
			} finally {
				activeAsyncTasks.decrementAndGet();
			}
		};
	}

	/**
	 * Teleports the given entity asynchronously.
	 * <p>
	 * Regionized servers (Folia) only support asynchronous teleports. Servers without Paper's
	 * asynchronous teleport API teleport the entity right away.
	 * 
	 * @param entity
	 *            the entity
	 * @param location
	 *            the destination
	 * @return a future that completes with the result of the teleport
	 */
	public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
		Validate.notNull(entity, "entity is null");
		Validate.notNull(location, "location is null");
		return getBackend().teleportAsync(entity, location);
	}

	/**
	 * Gets the current task generation.
	 * <p>
	 * The generation changes whenever all tasks are cancelled via {@link #cancelAllTasks(Plugin)}.
	 * Long running asynchronous work can compare it to detect that it got cancelled.
	 * 
	 * @return the current task generation
	 */
	public static int getTaskGeneration() {
		return taskGeneration;
	}

	/**
	 * Cancels all tasks of the plugin.
	 * <p>
	 * Region and entity tasks cannot be cancelled in bulk on Paper. Those tasks are skipped instead
	 * when they would run.
	 * 
	 * @param plugin
	 *            the plugin
	 */
	public static void cancelAllTasks(Plugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		taskGeneration++;
		getBackend().cancelTasks(plugin);
	}

	/**
	 * Checks if the current thread is a server thread that ticks the world.
	 * <p>
	 * On Folia, this returns <code>true</code> for every region thread and the global region thread.
	 * Use {@link #isOwnedByCurrentRegion(World, int, int)} to check if the current thread may
	 * access a specific chunk or entity.
	 * 
	 * @return <code>true</code> if currently running on a server thread
	 */
	public static boolean isMainThread() {
		return Bukkit.isPrimaryThread();
	}

	/**
	 * Checks if the current thread owns the specified chunk.
	 * <p>
	 * On servers with a single main thread, this is the case for the main thread.
	 * 
	 * @param world
	 *            the world
	 * @param chunkX
	 *            the chunk x coordinate
	 * @param chunkZ
	 *            the chunk z coordinate
	 * @return <code>true</code> if the chunk can be accessed from the current thread
	 */
	public static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
		Validate.notNull(world, "world is null");
		return getBackend().isOwnedByCurrentRegion(world, chunkX, chunkZ);
	}

	public static boolean isOwnedByCurrentRegion(Location location) {
		Validate.notNull(location, "location is null");
		World world = LocationUtils.getWorld(location);
		return isOwnedByCurrentRegion(
				world,
				ChunkCoords.fromBlock(location.getBlockX()),
				ChunkCoords.fromBlock(location.getBlockZ())
		);
	}

	public static boolean isOwnedByCurrentRegion(Entity entity) {
		Validate.notNull(entity, "entity is null");
		return getBackend().isOwnedByCurrentRegion(entity);
	}

	/**
	 * Schedules the given task to be run on the primary thread if required.
	 * <p>
	 * If the current thread is already a server thread, the task will be run immediately.
	 * Otherwise, it attempts to schedule the task to run as a global task. However, if the plugin
	 * is disabled, the task won't be scheduled.
	 * <p>
	 * Use this only for tasks that do not access worlds or entities.
	 * 
	 * @param plugin
	 *            the plugin to use for scheduling, not <code>null</code>
	 * @param task
	 *            the task, not <code>null</code>
	 * @return <code>true</code> if the task was run or successfully scheduled to be run,
	 *         <code>false</code> otherwise
	 */
	public static boolean runOnMainThreadOrOmit(Plugin plugin, Runnable task) {
		validatePluginTask(plugin, task);
		if (isMainThread()) {
			PluginLock.run(task);
			return true;
		} else {
			return (runTaskOrOmit(plugin, task) != null);
		}
	}

	/**
	 * Runs the given task on the thread that owns the specified chunk.
	 * <p>
	 * If the current thread already owns the chunk, the task is run immediately. Otherwise, it is
	 * scheduled for the next tick of the owning region.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param world
	 *            the world
	 * @param chunkX
	 *            the chunk x coordinate
	 * @param chunkZ
	 *            the chunk z coordinate
	 * @param task
	 *            the task
	 * @return <code>true</code> if the task was run or successfully scheduled to be run
	 */
	public static boolean runInRegionOrOmit(
			Plugin plugin,
			World world,
			int chunkX,
			int chunkZ,
			Runnable task
	) {
		validatePluginTask(plugin, task);
		if (isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
			PluginLock.run(task);
			return true;
		} else {
			return (runRegionTaskLaterOrOmit(plugin, world, chunkX, chunkZ, task, 0L) != null);
		}
	}

	public static boolean runInRegionOrOmit(Plugin plugin, Location location, Runnable task) {
		Validate.notNull(location, "location is null");
		return runInRegionOrOmit(
				plugin,
				LocationUtils.getWorld(location),
				ChunkCoords.fromBlock(location.getBlockX()),
				ChunkCoords.fromBlock(location.getBlockZ()),
				task
		);
	}

	/**
	 * Runs the given task on the thread that owns the specified entity.
	 * <p>
	 * If the current thread already owns the entity, the task is run immediately. Otherwise, it is
	 * scheduled for the next tick of the entity. If the entity is removed before that, the task is
	 * not run.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param entity
	 *            the entity
	 * @param task
	 *            the task
	 * @return <code>true</code> if the task was run or successfully scheduled to be run
	 */
	public static boolean runOnEntityOrOmit(Plugin plugin, Entity entity, Runnable task) {
		validatePluginTask(plugin, task);
		Validate.notNull(entity, "entity is null");
		if (isOwnedByCurrentRegion(entity)) {
			PluginLock.run(task);
			return true;
		} else {
			return (runEntityTaskLaterOrOmit(plugin, entity, task, null, 0L) != null);
		}
	}

	/**
	 * Runs the given cleanup task on the thread that owns the specified entity.
	 * <p>
	 * If the current thread already owns the entity, the task is run immediately. Otherwise, it is
	 * scheduled for the next tick of the entity. Unlike other tasks, this task is not skipped if
	 * {@link #cancelAllTasks(Plugin)} is called in the meantime, e.g. during a plugin reload.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param entity
	 *            the entity
	 * @param task
	 *            the task
	 */
	public static void runEntityCleanupTask(Plugin plugin, Entity entity, Runnable task) {
		validatePluginTask(plugin, task);
		Validate.notNull(entity, "entity is null");
		if (isOwnedByCurrentRegion(entity)) {
			PluginLock.run(task);
			return;
		}
		if (!plugin.isEnabled()) return;
		try {
			getBackend().runEntity(plugin, entity, wrapCleanup(plugin, task), null, 0L);
		} catch (IllegalPluginAccessException e) {
			// Couldn't register task: The plugin got disabled just now.
		}
	}

	/**
	 * Runs the given cleanup task on the thread that owns the specified location.
	 * <p>
	 * If the current thread already owns the location, the task is run immediately. Otherwise, it
	 * is scheduled for the next tick of the owning region. Unlike other tasks, this task is not
	 * skipped if {@link #cancelAllTasks(Plugin)} is called in the meantime, e.g. during a plugin
	 * reload.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param location
	 *            the location
	 * @param task
	 *            the task
	 */
	public static void runRegionCleanupTask(Plugin plugin, Location location, Runnable task) {
		validatePluginTask(plugin, task);
		Validate.notNull(location, "location is null");
		if (isOwnedByCurrentRegion(location)) {
			PluginLock.run(task);
			return;
		}
		if (!plugin.isEnabled()) return;
		try {
			getBackend().runRegion(
					plugin,
					LocationUtils.getWorld(location),
					ChunkCoords.fromBlock(location.getBlockX()),
					ChunkCoords.fromBlock(location.getBlockZ()),
					wrapCleanup(plugin, task),
					0L
			);
		} catch (IllegalPluginAccessException e) {
			// Couldn't register task: The plugin got disabled just now.
		}
	}

	public static @Nullable TaskHandle runTaskOrOmit(Plugin plugin, Runnable task) {
		return runTaskLaterOrOmit(plugin, task, 0L);
	}

	/**
	 * Schedules a global task.
	 * <p>
	 * On Folia, global tasks run on the global region thread and must not access worlds, chunks or
	 * entities.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param task
	 *            the task
	 * @param delay
	 *            the delay in ticks
	 * @return the task, or <code>null</code> if the task could not be scheduled
	 */
	public static @Nullable TaskHandle runTaskLaterOrOmit(
			Plugin plugin,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		// Tasks can only be registered while enabled:
		if (plugin.isEnabled()) {
			try {
				return getBackend().runGlobal(plugin, wrapSync(plugin, task), delay);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable TaskHandle runTaskTimerOrOmit(
			Plugin plugin,
			Runnable task,
			long delay,
			long period
	) {
		validatePluginTask(plugin, task);
		if (plugin.isEnabled()) {
			try {
				return getBackend().runGlobalTimer(plugin, wrapSync(plugin, task), delay, period);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable TaskHandle runRegionTaskLaterOrOmit(
			Plugin plugin,
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(world, "world is null");
		if (plugin.isEnabled()) {
			try {
				return getBackend().runRegion(
						plugin,
						world,
						chunkX,
						chunkZ,
						wrapSync(plugin, task),
						delay
				);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable TaskHandle runRegionTaskLaterOrOmit(
			Plugin plugin,
			Location location,
			Runnable task,
			long delay
	) {
		Validate.notNull(location, "location is null");
		return runRegionTaskLaterOrOmit(
				plugin,
				LocationUtils.getWorld(location),
				ChunkCoords.fromBlock(location.getBlockX()),
				ChunkCoords.fromBlock(location.getBlockZ()),
				task,
				delay
		);
	}

	public static @Nullable TaskHandle runRegionTaskTimerOrOmit(
			Plugin plugin,
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			long delay,
			long period
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(world, "world is null");
		if (plugin.isEnabled()) {
			try {
				return getBackend().runRegionTimer(
						plugin,
						world,
						chunkX,
						chunkZ,
						wrapSync(plugin, task),
						delay,
						period
				);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	/**
	 * Schedules a task for the given entity.
	 * <p>
	 * The task follows the entity across regions. If the entity is removed before the task runs,
	 * the task is not run, but the optional retired callback is.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param entity
	 *            the entity
	 * @param task
	 *            the task
	 * @param retired
	 *            the callback for when the entity has been removed, can be <code>null</code>
	 * @param delay
	 *            the delay in ticks
	 * @return the task, or <code>null</code> if the task could not be scheduled
	 */
	public static @Nullable TaskHandle runEntityTaskLaterOrOmit(
			Plugin plugin,
			Entity entity,
			Runnable task,
			@Nullable Runnable retired,
			long delay
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(entity, "entity is null");
		if (plugin.isEnabled()) {
			try {
				return getBackend().runEntity(
						plugin,
						entity,
						wrapSync(plugin, task),
						wrapRetired(plugin, retired),
						delay
				);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable TaskHandle runEntityTaskOrOmit(
			Plugin plugin,
			Entity entity,
			Runnable task
	) {
		return runEntityTaskLaterOrOmit(plugin, entity, task, null, 0L);
	}

	public static @Nullable TaskHandle runEntityTaskTimerOrOmit(
			Plugin plugin,
			Entity entity,
			Runnable task,
			@Nullable Runnable retired,
			long delay,
			long period
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(entity, "entity is null");
		if (plugin.isEnabled()) {
			try {
				return getBackend().runEntityTimer(
						plugin,
						entity,
						wrapSync(plugin, task),
						wrapRetired(plugin, retired),
						delay,
						period
				);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable TaskHandle runAsyncTaskOrOmit(Plugin plugin, Runnable task) {
		return runAsyncTaskLaterOrOmit(plugin, task, 0L);
	}

	public static @Nullable TaskHandle runAsyncTaskLaterOrOmit(
			Plugin plugin,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		// Tasks can only be registered while enabled:
		if (plugin.isEnabled()) {
			try {
				return getBackend().runAsync(plugin, wrapAsync(plugin, task), delay);
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	/**
	 * Awaits the completion of async tasks of the specified plugin.
	 * <p>
	 * If a logger is specified, it will be used to print informational messages suited to the
	 * context of this method being called during disabling of the plugin.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param asyncTasksTimeoutSeconds
	 *            the duration to wait for async tasks to finish in seconds (can be <code>0</code>)
	 * @param logger
	 *            the logger used for printing informational messages, can be <code>null</code>
	 * @return the number of remaining async tasks that are still running after waiting for the
	 *         specified duration
	 */
	public static int awaitAsyncTasksCompletion(
			Plugin plugin,
			int asyncTasksTimeoutSeconds,
			@Nullable Logger logger
	) {
		Validate.notNull(plugin, "plugin is null");
		Validate.isTrue(asyncTasksTimeoutSeconds >= 0, "asyncTasksTimeoutSeconds cannot be negative");

		int activeAsyncTasks = getActiveAsyncTasks(plugin);
		if (activeAsyncTasks > 0 && asyncTasksTimeoutSeconds > 0) {
			if (logger != null) {
				logger.info("Waiting up to " + asyncTasksTimeoutSeconds + " seconds for "
						+ activeAsyncTasks + " remaining async tasks to finish ...");
			}

			final long asyncTasksTimeoutMillis = TimeUnit.SECONDS.toMillis(asyncTasksTimeoutSeconds);
			final long waitStartNanos = System.nanoTime();
			long waitDurationMillis = 0L;
			do {
				// Periodically check again:
				try {
					Thread.sleep(25L);
				} catch (InterruptedException e) {
					// Ignore, but reset interrupt flag:
					Thread.currentThread().interrupt();
				}
				// Update the number of active async task before breaking from loop:
				activeAsyncTasks = getActiveAsyncTasks(plugin);

				// Update waiting duration and compare to timeout:
				waitDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStartNanos);
				if (waitDurationMillis > asyncTasksTimeoutMillis) {
					// Timeout reached, abort waiting..
					break;
				}
			} while (activeAsyncTasks > 0);

			if (waitDurationMillis > 1 && logger != null) {
				logger.info("Waited " + waitDurationMillis + " ms for async tasks to finish.");
			}
		}

		if (activeAsyncTasks > 0 && logger != null) {
			// Severe, since this can potentially result in data loss, depending on what the tasks
			// are doing:
			logger.severe("There are still " + activeAsyncTasks
					+ " remaining async tasks active! Disabling anyway now.");
		}
		return activeAsyncTasks;
	}

	private SchedulerUtils() {
	}
}
