package com.nisovin.shopkeepers.itemsadder;

import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.dependencies.itemsadder.ItemsAdderDependency;
import com.nisovin.shopkeepers.util.annotations.ReadOnly;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;

/**
 * Integration with the ItemsAdder plugin that automatically keeps the ItemsAdder items stored by
 * the Shopkeepers plugin up-to-date.
 * <p>
 * ItemsAdder items are only loosely coupled to their item configurations: Changes to an item's
 * configuration are not automatically reflected by previously created item stacks. Outdated item
 * stacks are known to cause issues in trading related contexts, e.g. items no longer matching, or
 * players receiving differently looking items than expected.
 * <p>
 * This integration resolves this for the items stored by the Shopkeepers plugin:
 * <ul>
 * <li>Whenever ItemsAdder loads or reloads its item configurations, we trigger an update of all
 * stored items ({@link SKShopkeepersPlugin#updateItems()}).
 * <li>Whenever an {@link com.nisovin.shopkeepers.api.events.UpdateItemEvent} is called for an item
 * that is detected to be an ItemsAdder item, we replace the item with a freshly created instance
 * based on ItemsAdder's current item configuration.
 * <li>As a safety net, whenever a trade would give an outdated ItemsAdder item to the trading
 * player, we replace the result item with a freshly created instance.
 * <li>When a player shopkeeper checks its containers for the items it has in stock, we identify
 * ItemsAdder items by their namespaced id instead of comparing them strictly (see
 * {@link #stockPredicate(UnmodifiableItemStack)}).
 * </ul>
 */
public class ItemsAdderIntegration {

	// Set while the integration is active. Static, so that the shopkeeper implementations can
	// consult the matcher without having to reach through the plugin instance.
	private static @Nullable ItemsAdderItemMatcher matcher = null;

	/**
	 * Gets a {@link Predicate} that accepts the items that count as stock for the given offer item.
	 * <p>
	 * Unless the ItemsAdder integration is active and the given item is an ItemsAdder item, this
	 * falls back to Shopkeepers' normal strict item comparison.
	 *
	 * @param offerItem
	 *            the offer item, not <code>null</code>
	 * @return the Predicate, not <code>null</code>
	 */
	public static Predicate<@ReadOnly @Nullable ItemStack> stockPredicate(
			UnmodifiableItemStack offerItem
	) {
		Validate.notNull(offerItem, "offerItem is null");
		ItemsAdderItemMatcher matcher = ItemsAdderIntegration.matcher;
		// The setting is checked here, rather than when the matcher is set up, so that it can be
		// toggled via a config reload without requiring a server restart:
		if (matcher == null || !Settings.itemsAdderMatchStockById) {
			return ItemUtils.similarItems(offerItem);
		}
		return matcher.stockPredicate(offerItem);
	}

	/**
	 * Checks whether the given item counts as stock for the given offer item.
	 *
	 * @param offerItem
	 *            the offer item, not <code>null</code>
	 * @param item
	 *            the item to check, can be <code>null</code>
	 * @return <code>true</code> if the item counts as stock for the offer item
	 */
	public static boolean matchesStock(
			UnmodifiableItemStack offerItem,
			@ReadOnly @Nullable ItemStack item
	) {
		return stockPredicate(offerItem).test(item);
	}

	private final SKShopkeepersPlugin plugin;
	private @Nullable ItemsAdderListener listener = null;

	public ItemsAdderIntegration(SKShopkeepersPlugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		this.plugin = plugin;
	}

	public void onEnable() {
		if (!Settings.enableItemsAdderIntegration) return;
		if (ItemsAdderDependency.getPlugin() == null) return;

		Log.info("ItemsAdder found: Automatically updating stored ItemsAdder items.");
		ItemsAdderListener listener = new ItemsAdderListener(plugin);
		this.listener = listener;
		Bukkit.getPluginManager().registerEvents(listener, plugin);
		matcher = new ItemsAdderItemMatcher();
	}

	public void onDisable() {
		matcher = null;
		ItemsAdderListener listener = this.listener;
		if (listener != null) {
			HandlerList.unregisterAll(listener);
			this.listener = null;
		}
	}
}
