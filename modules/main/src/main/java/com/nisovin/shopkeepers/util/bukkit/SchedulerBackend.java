package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Schedules tasks on the server.
 * <p>
 * The tasks passed to these methods are already wrapped (plugin lock, reload guard, async task
 * tracking). Delays and periods are in ticks.
 */
interface SchedulerBackend {

	public TaskHandle runGlobal(Plugin plugin, Runnable task, long delay);

	public TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long delay, long period);

	public TaskHandle runRegion(
			Plugin plugin,
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			long delay
	);

	public TaskHandle runRegionTimer(
			Plugin plugin,
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			long delay,
			long period
	);

	// Returns null if the entity has already been removed.
	public @Nullable TaskHandle runEntity(
			Plugin plugin,
			Entity entity,
			Runnable task,
			@Nullable Runnable retired,
			long delay
	);

	// Returns null if the entity has already been removed.
	public @Nullable TaskHandle runEntityTimer(
			Plugin plugin,
			Entity entity,
			Runnable task,
			@Nullable Runnable retired,
			long delay,
			long period
	);

	public TaskHandle runAsync(Plugin plugin, Runnable task, long delay);

	public CompletableFuture<Boolean> teleportAsync(Entity entity, Location location);

	public boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ);

	public boolean isOwnedByCurrentRegion(Entity entity);

	// Only cancels the tasks that the server is able to cancel in bulk. Region and entity tasks
	// are discarded via the reload guard instead.
	public void cancelTasks(Plugin plugin);
}
