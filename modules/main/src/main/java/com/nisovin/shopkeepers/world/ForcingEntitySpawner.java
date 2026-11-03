package com.nisovin.shopkeepers.world;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.util.bukkit.EventUtils;
import com.nisovin.shopkeepers.util.bukkit.LocationUtils;
import com.nisovin.shopkeepers.util.logging.Log;

/**
 * Tries to bypass other plugins that might block the spawning of entities (e.g. region protection
 * plugins).
 */
public class ForcingEntitySpawner implements Listener {

	private final SKShopkeepersPlugin plugin;

	private @Nullable Location nextSpawnLocation = null;
	private @Nullable EntityType nextEntityType = null;

	public ForcingEntitySpawner(SKShopkeepersPlugin plugin) {
		this.plugin = plugin;
	}

	public void onEnable() {
		EventUtils.registerEvents(this, plugin);
	}

	public void onDisable() {
		HandlerList.unregisterAll(this);

		// Reset any pending forced spawn:
		this.resetForcedEntitySpawn();
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	void onEntitySpawn(EntitySpawnEvent event) {
		if (nextSpawnLocation == null) return;
		if (this.matchesForcedCreatureSpawn(event)) {
			event.setCancelled(false);
		} else {
			// Unexpected.
			Log.debug(() -> "Forced entity spawning seems to be out of sync: "
					+ "Forced spawning was activated for an entity of type " + nextEntityType
					+ " at location " + nextSpawnLocation + ", but a different entity of type "
					+ event.getEntityType() + " was spawned at location " + event.getLocation()
					+ ".");
		}

		this.resetForcedEntitySpawn();
	}

	private boolean matchesForcedCreatureSpawn(EntitySpawnEvent event) {
		return event.getEntityType() == nextEntityType
				&& LocationUtils.getSafeDistanceSquared(event.getLocation(), nextSpawnLocation) < 0.6D;
	}

	/**
	 * Tries to force the subsequent spawn attempt for the specified entity type at the specified
	 * location.
	 * 
	 * @param location
	 *            the spawn location
	 * @param entityType
	 *            the entity type
	 */
	public void forceEntitySpawn(Location location, EntityType entityType) {
		this.nextSpawnLocation = location;
		this.nextEntityType = entityType;
	}

	/**
	 * Resets any pending forced entity spawn.
	 */
	public void resetForcedEntitySpawn() {
		nextSpawnLocation = null;
		nextEntityType = null;
	}
}
