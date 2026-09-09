package com.nisovin.shopkeepers.itemsadder;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.events.UpdateItemEvent;
import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.ui.DefaultUITypes;
import com.nisovin.shopkeepers.api.ui.UISession;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.config.Settings;
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
	 *         if the item is not an ItemsAdder item, is excluded from the item updates, no longer
	 *         exists in ItemsAdder's item registry, or is already up-to-date
	 */
	private @Nullable ItemStack recreateItem(UnmodifiableItemStack item) {
		assert item != null && !ItemUtils.isEmpty(item);
		ItemStack itemCopy = item.copy();
		// Note: This intentionally only detects items that ItemsAdder itself can positively
		// identify (i.e. items that carry ItemsAdder's identifying data). Guessing the identity of
		// legacy untagged items, e.g. based on their custom model data, is not safe: Custom model
		// data assignments can shift between resource pack versions, e.g. towards auto-generated
		// auxiliary items such as animation frames.
		CustomStack customStack = CustomStack.byItemStack(itemCopy);
		if (customStack == null) return null; // Not an ItemsAdder item

		// The ItemsAdder API is not annotated: A valid CustomStack always has a namespaced id.
		String namespacedId = Unsafe.assertNonNull(customStack.getNamespacedID());
		if (isExcluded(namespacedId)) {
			Log.debug(DebugOptions.itemUpdates, () -> "Skipping the update of the excluded"
					+ " ItemsAdder item '" + namespacedId + "'.");
			return null;
		}

		CustomStack freshCustomStack = CustomStack.getInstance(namespacedId);
		if (freshCustomStack == null) return null; // No longer exists in the item registry

		ItemStack freshItem = freshCustomStack.getItemStack();
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
	 * ItemsAdder generates fresh ids for an item's attribute modifiers whenever it (re)loads its
	 * item configurations. A plain equality check would therefore consider every stored item that
	 * carries an attribute modifier to be outdated on every server start, and rewrite it to a copy
	 * with identical content. Differences that only involve the modifier ids are ignored here.
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

		if (!ItemsAdderStockMatching.copyAttributeModifierIds(storedMeta, freshMeta)) {
			// The attribute modifiers differ in more than just their ids:
			return false;
		}

		freshItemCopy.setItemMeta(freshMeta);
		return storedItem.equals(freshItemCopy);
	}

	/**
	 * Checks whether the given ItemsAdder item is excluded from the item updates.
	 *
	 * @param namespacedId
	 *            the item's ItemsAdder namespaced id, not <code>null</code>
	 * @return <code>true</code> if the item is excluded
	 */
	static boolean isExcluded(String namespacedId) {
		for (String excludedId : Settings.itemsAdderItemUpdateExclusions) {
			if (excludedId.equalsIgnoreCase(namespacedId)) return true;
		}
		return false;
	}

	/**
	 * Copies the custom tags (i.e. the contents of the
	 * {@link ItemMeta#getPersistentDataContainer() persistent data container}) of the given source
	 * item that the given target item does not define itself over to the target item.
	 * <p>
	 * ItemsAdder item configurations cannot represent the custom tags that other plugins may have
	 * added to an item, e.g. CMI's attached commands. Recreating an item from its ItemsAdder item
	 * configuration would therefore silently strip these tags and thereby break the functionality
	 * that other plugins associate with the item.
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

		// Tags that ItemsAdder defines itself take precedence:
		PersistentDataContainer targetTags = targetMeta.getPersistentDataContainer();
		sourceTags.copyTo(targetTags, false);
		targetItem.setItemMeta(targetMeta);
	}
}
