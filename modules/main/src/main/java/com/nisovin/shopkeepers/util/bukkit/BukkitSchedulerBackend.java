package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Uses the Bukkit scheduler.
 * <p>
 * Only used on servers without Paper's region-aware schedulers (Spigot, test environments). All
 * synchronous tasks run on the main thread.
 */
final class BukkitSchedulerBackend implements SchedulerBackend {

	private static final class BukkitTaskHandle implements TaskHandle {

		private final BukkitTask task;

		BukkitTaskHandle(BukkitTask task) {
			this.task = task;
		}

		@Override
		public void cancel() {
			task.cancel();
		}

		@Override
		public boolean isCancelled() {
			return task.isCancelled();
		}
	}

	private static TaskHandle handle(BukkitTask task) {
		return new BukkitTaskHandle(task);
	}

	BukkitSchedulerBackend() {
	}

	@Override
	public TaskHandle runGlobal(Plugin plugin, Runnable task, long delay) {
		return handle(Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delay)));
	}

	@Override
	public TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long delay, long period) {
		return handle(Bukkit.getScheduler().runTaskTimer(
				plugin,
				task,
				Math.max(0L, delay),
				Math.max(1L, period)
		));
	}

	@Override
	public TaskHandle runRegion(
			Plugin plugin,
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			long delay
	) {
		return this.runGlobal(plugin, task, delay);
	}

	@Override
	public TaskHandle runRegionTimer(
			Plugin plugin,
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			long delay,
			long period
	) {
		return this.runGlobalTimer(plugin, task, delay, period);
	}

	@Override
	public @Nullable TaskHandle runEntity(
			Plugin plugin,
			Entity entity,
			Runnable task,
			@Nullable Runnable retired,
			long delay
	) {
		return this.runGlobal(plugin, task, delay);
	}

	@Override
	public @Nullable TaskHandle runEntityTimer(
			Plugin plugin,
			Entity entity,
			Runnable task,
			@Nullable Runnable retired,
			long delay,
			long period
	) {
		return this.runGlobalTimer(plugin, task, delay, period);
	}

	@Override
	public TaskHandle runAsync(Plugin plugin, Runnable task, long delay) {
		return handle(Bukkit.getScheduler().runTaskLaterAsynchronously(
				plugin,
				task,
				Math.max(0L, delay)
		));
	}

	@Override
	public CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
		return CompletableFuture.completedFuture(entity.teleport(location));
	}

	@Override
	public boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
		return Bukkit.isPrimaryThread();
	}

	@Override
	public boolean isOwnedByCurrentRegion(Entity entity) {
		return Bukkit.isPrimaryThread();
	}

	@Override
	public void cancelTasks(Plugin plugin) {
		Bukkit.getScheduler().cancelTasks(plugin);
	}
}
