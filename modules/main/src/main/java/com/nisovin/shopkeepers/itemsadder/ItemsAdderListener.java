package com.nisovin.shopkeepers.itemsadder;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.events.UpdateItemEvent;
import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.ui.DefaultUITypes;
import com.nisovin.shopkeepers.api.ui.UISession;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.debug.DebugOptions;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;

import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import dev.lone.itemsadder.api.ItemsAdder;

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
	// Fallback index to identify legacy ItemsAdder items that were created before ItemsAdder
	// tagged its items with their id: Maps the material and custom model data of the current
	// ItemsAdder items to their namespaced id.
	private final Map<Material, Map<Float, String>> itemIdsByModelData = new HashMap<>();

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
			this.rebuildModelDataIndex();
			int updatedItems = plugin.updateItems();
			Log.info("ItemsAdder loaded its items: Updated " + updatedItems + " stored items.");
		});
	}

	private void rebuildModelDataIndex() {
		itemIdsByModelData.clear();
		for (CustomStack customStack : ItemsAdder.getAllItems()) {
			if (customStack == null) continue;
			ItemStack item = customStack.getItemStack();
			if (ItemUtils.isEmpty(item)) continue;

			ItemMeta itemMeta = item.getItemMeta();
			if (itemMeta == null || !itemMeta.hasCustomModelData()) continue;

			String namespacedId = Unsafe.assertNonNull(customStack.getNamespacedID());
			Material material = item.getType();
			Map<Float, String> itemIds = itemIdsByModelData.get(material);
			if (itemIds == null) {
				itemIds = new HashMap<>();
				itemIdsByModelData.put(material, itemIds);
			}
			for (Float modelData : itemMeta.getCustomModelDataComponent().getFloats()) {
				if (modelData == null) continue;
				itemIds.putIfAbsent(modelData, namespacedId);
			}
		}
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

	// ItemsAdder cancels TradeSelectEvents when it suspects that a trade might consume custom
	// items as if they were vanilla items (e.g. when a stored cost item predates ItemsAdder's
	// item tagging and the player carries a custom item of the same material). Within shopkeeper
	// trading UIs, this protection is unnecessary (Shopkeepers itself verifies that the traded
	// items match the trading recipe) and harmful: The cancelled trade selection desyncs the
	// player's selected trade from the server, resulting in players receiving the items of a
	// previously selected trade.
	@EventHandler(priority = EventPriority.HIGHEST)
	void onTradeSelect(TradeSelectEvent event) {
		if (!event.isCancelled()) return;
		if (!(event.getWhoClicked() instanceof Player player)) return;

		UISession uiSession = plugin.getUIRegistry().getUISession(player);
		if (uiSession == null) return;
		if (uiSession.getUIType() != DefaultUITypes.TRADING()) return;

		event.setCancelled(false);
		Log.debug(() -> "Reverted the cancelled trade selection of player " + player.getName()
				+ " inside the shopkeeper trading UI.");
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
		String namespacedId;
		CustomStack customStack = CustomStack.byItemStack(item.copy());
		if (customStack != null) {
			namespacedId = customStack.getNamespacedID();
		} else {
			// Fallback for legacy items without identifying ItemsAdder data:
			namespacedId = this.getItemIdByModelData(item);
			if (namespacedId == null) return null; // Not an ItemsAdder item
		}

		CustomStack freshCustomStack = CustomStack.getInstance(namespacedId);
		if (freshCustomStack == null) return null; // No longer exists in the item registry

		ItemStack freshItem = freshCustomStack.getItemStack();
		if (ItemUtils.isEmpty(freshItem)) return null;

		freshItem = freshItem.clone();
		freshItem.setAmount(item.getAmount());
		if (item.equals(freshItem)) return null; // The item is already up-to-date

		return freshItem;
	}

	private @Nullable String getItemIdByModelData(UnmodifiableItemStack item) {
		Map<Float, String> itemIds = itemIdsByModelData.get(item.getType());
		if (itemIds == null) return null;

		ItemMeta itemMeta = item.getItemMeta();
		if (itemMeta == null || !itemMeta.hasCustomModelData()) return null;

		for (Float modelData : itemMeta.getCustomModelDataComponent().getFloats()) {
			if (modelData == null) continue;
			String namespacedId = itemIds.get(modelData);
			if (namespacedId != null) return namespacedId;
		}
		return null;
	}
}
