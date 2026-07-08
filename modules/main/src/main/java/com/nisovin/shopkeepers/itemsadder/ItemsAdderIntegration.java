package com.nisovin.shopkeepers.itemsadder;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.dependencies.itemsadder.ItemsAdderDependency;
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
 * </ul>
 */
public class ItemsAdderIntegration {

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
	}

	public void onDisable() {
		ItemsAdderListener listener = this.listener;
		if (listener != null) {
			HandlerList.unregisterAll(listener);
			this.listener = null;
		}
	}
}
