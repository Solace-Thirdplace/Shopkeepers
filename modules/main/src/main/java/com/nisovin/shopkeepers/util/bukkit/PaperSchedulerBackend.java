package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Uses Paper's region-aware schedulers.
 * <p>
 * These schedulers exist on all Paper based servers. On Folia, they run the tasks on the thread
 * that owns the targeted region or entity. On servers with a single main thread, they run the
 * tasks on the main thread.
 */
final class PaperSchedulerBackend implements SchedulerBackend {

	private static final class PaperTaskHandle implements TaskHandle {

		private final ScheduledTask task;

		PaperTaskHandle(ScheduledTask task) {
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

	private static TaskHandle handle(ScheduledTask task) {
		return new PaperTaskHandle(task);
	}

	private static @Nullable TaskHandle nullableHandle(@Nullable ScheduledTask task) {
		return (task != null) ? new PaperTaskHandle(task) : null;
	}

	PaperSchedulerBackend() {
	}

	@Override
	public TaskHandle runGlobal(Plugin plugin, Runnable task, long delay) {
		if (delay <= 0) {
			return handle(Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run()));
		} else {
			return handle(Bukkit.getGlobalRegionScheduler().runDelayed(
					plugin,
					t -> task.run(),
					delay
			));
		}
	}

	@Override
	public TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long delay, long period) {
		return handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(
				plugin,
				t -> task.run(),
				Math.max(1L, delay),
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
		if (delay <= 0) {
			return handle(Bukkit.getRegionScheduler().run(
					plugin,
					world,
					chunkX,
					chunkZ,
					t -> task.run()
			));
		} else {
			return handle(Bukkit.getRegionScheduler().runDelayed(
					plugin,
					world,
					chunkX,
					chunkZ,
					t -> task.run(),
					delay
			));
		}
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
		return handle(Bukkit.getRegionScheduler().runAtFixedRate(
				plugin,
				world,
				chunkX,
				chunkZ,
				t -> task.run(),
				Math.max(1L, delay),
				Math.max(1L, period)
		));
	}

	@Override
	public @Nullable TaskHandle runEntity(
			Plugin plugin,
			Entity entity,
			Runnable task,
			@Nullable Runnable retired,
			long delay
	) {
		if (delay <= 0) {
			return nullableHandle(entity.getScheduler().run(plugin, t -> task.run(), retired));
		} else {
			return nullableHandle(entity.getScheduler().runDelayed(
					plugin,
					t -> task.run(),
					retired,
					delay
			));
		}
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
		return nullableHandle(entity.getScheduler().runAtFixedRate(
				plugin,
				t -> task.run(),
				retired,
				Math.max(1L, delay),
				Math.max(1L, period)
		));
	}

	@Override
	public TaskHandle runAsync(Plugin plugin, Runnable task, long delay) {
		if (delay <= 0) {
			return handle(Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run()));
		} else {
			return handle(Bukkit.getAsyncScheduler().runDelayed(
					plugin,
					t -> task.run(),
					delay * 50L,
					TimeUnit.MILLISECONDS
			));
		}
	}

	@Override
	public CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
		return entity.teleportAsync(location);
	}

	@Override
	public boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
		return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
	}

	@Override
	public boolean isOwnedByCurrentRegion(Entity entity) {
		return Bukkit.isOwnedByCurrentRegion(entity);
	}

	@Override
	public void cancelTasks(Plugin plugin) {
		Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
		Bukkit.getAsyncScheduler().cancelTasks(plugin);
	}
}
