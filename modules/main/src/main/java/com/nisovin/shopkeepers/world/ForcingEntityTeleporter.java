package com.nisovin.shopkeepers.world;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.util.bukkit.EventUtils;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.logging.Log;

/**
 * Tries to force an entity teleport, bypassing all other plugins (including this plugin).
 * <p>
 * After a change in the Paper server (PR 9937) in MC 1.20.2 to now also call the
 * {@link EntityTeleportEvent} for plugin invoked teleports, all plugins that previously cancelled
 * or modified entity teleports in certain cases (including this plugin itself) can now accidentally
 * cancel or modify plugin invoked entity teleports that were not supposed to be cancelled or
 * modified previously. For now, we restore the previous behavior for all shopkeeper entity
 * teleports by forcing these teleports.
 */
public class ForcingEntityTeleporter implements Listener {

	private final SKShopkeepersPlugin plugin;

	private @Nullable UUID nextTeleportEntityUuid = null;
	private @Nullable Location toLocation = null;
	// Set once a synchronous teleport fails because the server does not support it (Folia):
	private static volatile boolean syncTeleportUnsupported = false;

	public ForcingEntityTeleporter(SKShopkeepersPlugin plugin) {
		this.plugin = plugin;
	}

	public void onEnable() {
		EventUtils.registerEvents(this, plugin);
	}

	public void onDisable() {
		HandlerList.unregisterAll(this);

		// Reset any pending forced spawn:
		this.resetForcedEntityTeleport();
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	void onEntityTeleport(EntityTeleportEvent event) {
		if (nextTeleportEntityUuid == null) return;
		if (this.matchesForcedEntityTeleport(event)) {
			event.setCancelled(false);
			event.setTo(toLocation);
		} else {
			// Unexpected.
			Log.debug(() -> "Forced entity teleporting seems to be out of sync: A forced teleport "
					+ "was requested for entity with unique id " + nextTeleportEntityUuid
					+ ", but a different entity was teleported: "
					+ event.getEntity().getUniqueId());
		}

		this.resetForcedEntityTeleport();
	}

	private boolean matchesForcedEntityTeleport(EntityTeleportEvent event) {
		return event.getEntity().getUniqueId().equals(nextTeleportEntityUuid);
	}

	/**
	 * Teleports the given entity to the given location, trying to bypass any plugins that cancel or
	 * modify any corresponding {@link EntityTeleportEvent}.
	 * <p>
	 * On servers that do not support synchronous teleports (Folia), this uses
	 * {@link Entity#teleportAsync(Location)} instead. The teleport then completes later and its
	 * teleport event is not forced.
	 * 
	 * @param entity
	 *            the entity to teleport
	 * @param toLocation
	 *            the destination location
	 * @return <code>false</code> if the teleport failed right away
	 */
	public boolean teleport(Entity entity, Location toLocation) {
		CompletableFuture<Boolean> result = this.teleportWithResult(entity, toLocation);
		return !result.isDone() || Boolean.TRUE.equals(result.getNow(Boolean.FALSE));
	}

	/**
	 * Teleports the given entity to the given location, like {@link #teleport(Entity, Location)},
	 * and provides the result of the teleport.
	 * <p>
	 * The returned future is already completed if the teleport happened synchronously. On servers
	 * that do not support synchronous teleports (Folia), it completes once the asynchronous
	 * teleport completes.
	 * 
	 * @param entity
	 *            the entity to teleport
	 * @param toLocation
	 *            the destination location
	 * @return the result of the teleport
	 */
	public CompletableFuture<Boolean> teleportWithResult(Entity entity, Location toLocation) {
		if (!syncTeleportUnsupported) {
			try {
				return CompletableFuture.completedFuture(this.teleportSync(entity, toLocation));
			} catch (UnsupportedOperationException e) {
				// Folia: Synchronous teleports are not supported.
				syncTeleportUnsupported = true;
			}
		}

		this.nextTeleportEntityUuid = entity.getUniqueId();
		this.toLocation = toLocation;

		CompletableFuture<Boolean> result;
		try {
			result = SchedulerUtils.teleportAsync(entity, toLocation);
		} catch (IllegalStateException e) {
			// Folia: Another teleport of this entity is still in progress.
			Log.debug(() -> "Failed to teleport entity " + entity.getUniqueId() + ": "
					+ e.getMessage());
			return CompletableFuture.completedFuture(false);
		} finally {
			// This reset is required if the teleport did not actually trigger an event (e.g. on
			// Spigot instead of Paper servers), or if it completes later:
			this.resetForcedEntityTeleport();
		}

		// MC-44654: For some entities (e.g. end crystals), the server does not automatically send
		// an update packet to the client when their position changes. We force the client-side
		// update here by toggling some other entity property.
		result.thenAccept(success -> {
			if (!Boolean.TRUE.equals(success)) return;
			var customNameVisible = entity.isCustomNameVisible();
			entity.setCustomNameVisible(!customNameVisible);
			entity.setCustomNameVisible(customNameVisible);
		});

		return result;
	}

	private boolean teleportSync(Entity entity, Location toLocation) {
		this.nextTeleportEntityUuid = entity.getUniqueId();
		this.toLocation = toLocation;

		boolean result;
		try {
			result = entity.teleport(toLocation);
		} finally {
			// This reset is required if the teleport did not actually trigger an event (e.g. on
			// Spigot instead of Paper servers):
			this.resetForcedEntityTeleport();
		}

		// MC-44654: For some entities (e.g. end crystals), the server does not automatically send
		// an update packet to the client when their position changes. We force the client-side
		// update here by toggling some other entity property.
		var customNameVisible = entity.isCustomNameVisible();
		entity.setCustomNameVisible(!customNameVisible);
		entity.setCustomNameVisible(customNameVisible);

		return result;
	}

	/**
	 * Resets any pending forced entity teleport.
	 */
	public void resetForcedEntityTeleport() {
		nextTeleportEntityUuid = null;
		toLocation = null;
	}
}
