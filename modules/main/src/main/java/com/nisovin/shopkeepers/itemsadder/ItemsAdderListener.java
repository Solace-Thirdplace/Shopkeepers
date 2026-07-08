package com.nisovin.shopkeepers.itemsadder;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.events.UpdateItemEvent;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.debug.DebugOptions;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;

import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;

/**
 * Handles the events involved in the {@link ItemsAdderIntegration}.
 * <p>
 * This class references ItemsAdder API classes and shall therefore only be instantiated when the
 * ItemsAdder plugin is present.
 */
class ItemsAdderListener implements Listener {

	private final SKShopkeepersPlugin plugin;
	// Whether an item update triggered by an ItemsAdderLoadDataEvent is currently pending:
	private boolean updatePending = false;

	ItemsAdderListener(SKShopkeepersPlugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		this.plugin = plugin;
	}

	// Called whenever ItemsAdder has loaded or reloaded its item configurations, e.g. during
	// server startup, or when a server admin has applied item configuration changes via
	// '/iareload' or '/iazip'.
	@EventHandler
	void onItemsAdderLoadData(ItemsAdderLoadDataEvent event) {
		if (updatePending) return;
		updatePending = true;
		// Updating the items aborts any currently active UI sessions, so this is not safe to be
		// called from within an event handler:
		Bukkit.getScheduler().runTask(plugin, () -> {
			updatePending = false;
			int updatedItems = plugin.updateItems();
			Log.info("ItemsAdder loaded its items: Updated " + updatedItems + " stored items.");
		});
	}

	// Called for all stored items when the items are updated, e.g. triggered by the
	// ItemsAdderLoadDataEvent above, or manually via '/shopkeeper updateItems'.
	@EventHandler
	void onUpdateItem(UpdateItemEvent event) {
		ItemStack freshItem = this.recreateItem(event.getItem());
		if (freshItem == null) return;

		event.setItem(UnmodifiableItemStack.ofNonNull(freshItem));
		Log.debug(DebugOptions.itemUpdates, "Updated ItemsAdder item of type "
				+ freshItem.getType());
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
				+ ": Replaced the result item with a freshly created ItemsAdder item.");
	}

	/**
	 * Freshly recreates the given item based on ItemsAdder's current item configurations, if it is
	 * an ItemsAdder item.
	 *
	 * @param item
	 *            the item, not <code>null</code> or empty
	 * @return the freshly created item, with the original item's stack size, or <code>null</code>
	 *         if the item is not an ItemsAdder item, no longer exists in ItemsAdder's item
	 *         registry, or is already up-to-date
	 */
	private @Nullable ItemStack recreateItem(UnmodifiableItemStack item) {
		assert item != null && !ItemUtils.isEmpty(item);
		CustomStack customStack = CustomStack.byItemStack(item.copy());
		if (customStack == null) return null; // Not an ItemsAdder item

		CustomStack freshCustomStack = CustomStack.getInstance(customStack.getNamespacedID());
		if (freshCustomStack == null) return null; // No longer exists in the item registry

		ItemStack freshItem = freshCustomStack.getItemStack();
		if (ItemUtils.isEmpty(freshItem)) return null;

		freshItem = freshItem.clone();
		freshItem.setAmount(item.getAmount());
		if (item.equals(freshItem)) return null; // The item is already up-to-date

		return freshItem;
	}
}
