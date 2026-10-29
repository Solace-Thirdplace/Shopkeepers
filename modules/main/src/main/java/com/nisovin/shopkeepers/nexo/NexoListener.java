package com.nisovin.shopkeepers.nexo;

import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.events.UpdateItemEvent;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.debug.DebugOptions;
import com.nisovin.shopkeepers.dependencies.nexo.NexoDependency;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;

/**
 * Handles the events involved in the {@link NexoIntegration}.
 */
class NexoListener implements Listener {

	private final SKShopkeepersPlugin plugin;
	private final NexoDependency.Api nexoApi;
	// Whether an item update triggered by Nexo loading its items is currently pending:
	private final AtomicBoolean updatePending = new AtomicBoolean(false);

	NexoListener(SKShopkeepersPlugin plugin, NexoDependency.Api nexoApi) {
		Validate.notNull(plugin, "plugin is null");
		Validate.notNull(nexoApi, "nexoApi is null");
		this.plugin = plugin;
		this.nexoApi = nexoApi;
	}

	// Called whenever Nexo has loaded or reloaded its items, e.g. during server startup, or when a
	// server admin has applied item configuration changes via '/nexo reload items'.
	// Registered by the NexoIntegration, because the event class is only known at runtime.
	void onNexoItemsLoaded() {
		if (!updatePending.compareAndSet(false, true)) return;
		// Updating the items aborts any currently active UI sessions, so this is not safe to be
		// called from within an event handler. The event might also be called asynchronously.
		SchedulerUtils.runTaskOrOmit(plugin, () -> {
			updatePending.set(false);
			int updatedItems = plugin.updateItems();
			Log.info("Nexo loaded its items: Updated " + updatedItems + " stored items.");
		});
	}

	// Called for all stored items when the items are updated, e.g. triggered by Nexo loading its
	// items, or manually via '/shopkeeper updateItems'.
	@EventHandler
	void onUpdateItem(UpdateItemEvent event) {
		ItemStack freshItem = this.recreateItem(event.getItem());
		if (freshItem == null) return;

		event.setItem(UnmodifiableItemStack.ofNonNull(freshItem));
		Log.debug(DebugOptions.itemUpdates, "Updated Nexo item of type " + freshItem.getType());
	}

	// Safety net: Even if the stored trade offer is outdated, players receive an up-to-date item.
	@EventHandler(ignoreCancelled = true)
	void onShopkeeperTrade(ShopkeeperTradeEvent event) {
		UnmodifiableItemStack resultItem = event.getResultItem();
		if (resultItem == null) return;

		ItemStack freshItem = this.recreateItem(resultItem);
		if (freshItem == null) return;

		event.setResultItem(UnmodifiableItemStack.ofNonNull(freshItem));
		Log.debug(DebugOptions.itemUpdates, () -> "Trade with shopkeeper "
				+ event.getShopkeeper().getIdString()
				+ ": Replaced the result item with a freshly created Nexo item.");
	}

	/**
	 * Freshly recreates the given item based on Nexo's current item configurations, if it is a Nexo
	 * item.
	 *
	 * @param item
	 *            the item, not <code>null</code> or empty
	 * @return the freshly created item, with the original item's stack size, or <code>null</code>
	 *         if the item is not a Nexo item, is excluded from the item updates, no longer exists in
	 *         Nexo's item registry, or is already up-to-date
	 */
	private @Nullable ItemStack recreateItem(UnmodifiableItemStack item) {
		assert item != null && !ItemUtils.isEmpty(item);
		ItemStack itemCopy = item.copy();
		String itemId = NexoItemIds.getItemId(itemCopy);
		if (itemId == null) return null; // Not a Nexo item

		if (isExcluded(itemId)) {
			Log.debug(DebugOptions.itemUpdates, () -> "Skipping the update of the excluded"
					+ " Nexo item '" + itemId + "'.");
			return null;
		}

		ItemStack freshItem;
		try {
			freshItem = nexoApi.createItem(itemId);
		} catch (RuntimeException e) {
			Log.warning("Failed to create Nexo item '" + itemId + "'!", e);
			return null;
		}
		if (freshItem == null) return null; // No longer exists in the item registry
		if (ItemUtils.isEmpty(freshItem)) return null;

		freshItem = freshItem.clone();
		freshItem.setAmount(item.getAmount());
		copyMissingCustomTags(itemCopy, freshItem);
		if (isUpToDate(itemCopy, freshItem)) return null;

		return freshItem;
	}

	/**
	 * Checks whether the given stored item already matches the given freshly created item.
	 * <p>
	 * Differences that only involve the ids of attribute modifiers are ignored. Otherwise, an item
	 * that carries an attribute modifier with a freshly generated id would be considered outdated
	 * on every server start, and be rewritten to a copy with identical content.
	 *
	 * @param storedItem
	 *            the stored item, not <code>null</code>
	 * @param freshItem
	 *            the freshly created item, not <code>null</code>
	 * @return <code>true</code> if the stored item does not need to be updated
	 */
	private static boolean isUpToDate(ItemStack storedItem, ItemStack freshItem) {
		if (storedItem.equals(freshItem)) return true;

		@Nullable ItemMeta storedMeta = storedItem.getItemMeta();
		if (storedMeta == null) return false;

		ItemStack freshItemCopy = freshItem.clone();
		@Nullable ItemMeta freshMeta = freshItemCopy.getItemMeta();
		if (freshMeta == null) return false;

		if (!NexoStockMatching.copyAttributeModifierIds(storedMeta, freshMeta)) {
			// The attribute modifiers differ in more than just their ids:
			return false;
		}

		freshItemCopy.setItemMeta(freshMeta);
		return storedItem.equals(freshItemCopy);
	}

	/**
	 * Checks whether the given Nexo item is excluded from the item updates.
	 *
	 * @param itemId
	 *            the Nexo item id, not <code>null</code>
	 * @return <code>true</code> if the item is excluded
	 */
	static boolean isExcluded(String itemId) {
		for (String excludedId : Settings.nexoItemUpdateExclusions) {
			if (excludedId.equalsIgnoreCase(itemId)) return true;
		}
		return false;
	}

	/**
	 * Copies the custom tags (i.e. the contents of the
	 * {@link ItemMeta#getPersistentDataContainer() persistent data container}) of the given source
	 * item that the given target item does not define itself over to the target item.
	 * <p>
	 * Nexo item configurations cannot represent the custom tags that other plugins may have added
	 * to an item, e.g. CMI's attached commands. Recreating an item from its Nexo item configuration
	 * would therefore silently strip these tags and thereby break the functionality that other
	 * plugins associate with the item.
	 *
	 * @param sourceItem
	 *            the source item, not <code>null</code>
	 * @param targetItem
	 *            the target item, not <code>null</code>
	 */
	private static void copyMissingCustomTags(ItemStack sourceItem, ItemStack targetItem) {
		@Nullable ItemMeta sourceMeta = sourceItem.getItemMeta();
		if (sourceMeta == null) return;

		PersistentDataContainer sourceTags = sourceMeta.getPersistentDataContainer();
		if (sourceTags.isEmpty()) return;

		@Nullable ItemMeta targetMeta = targetItem.getItemMeta();
		if (targetMeta == null) return;

		// Tags that Nexo defines itself take precedence:
		PersistentDataContainer targetTags = targetMeta.getPersistentDataContainer();
		sourceTags.copyTo(targetTags, false);
		targetItem.setItemMeta(targetMeta);
	}
}
