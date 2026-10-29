package com.nisovin.shopkeepers.nexo;

import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.dependencies.nexo.NexoDependency;
import com.nisovin.shopkeepers.util.annotations.ReadOnly;
import com.nisovin.shopkeepers.util.bukkit.EventUtils;
import com.nisovin.shopkeepers.util.inventory.ItemIdentityTags;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;
import com.nisovin.shopkeepers.util.java.Validate;
import com.nisovin.shopkeepers.util.logging.Log;

/**
 * Integration with the Nexo plugin that automatically keeps the Nexo items stored by the
 * Shopkeepers plugin up-to-date.
 * <p>
 * Nexo items are only loosely coupled to their item configurations: Changes to an item's
 * configuration are not automatically reflected by item stacks that Nexo cannot reach, such as the
 * items stored by the Shopkeepers plugin. Outdated item stacks are known to cause issues in trading
 * related contexts, e.g. items no longer matching, or players receiving differently looking items
 * than expected.
 * <p>
 * Nexo identifies its items by the custom tag {@code nexo:id} (see {@link NexoItemIds}). This
 * integration keeps the stored items up-to-date:
 * <ul>
 * <li>Whenever Nexo loads or reloads its items, we trigger an update of all stored items
 * ({@link SKShopkeepersPlugin#updateItems()}).
 * <li>Whenever an {@link com.nisovin.shopkeepers.api.events.UpdateItemEvent} is called for a Nexo
 * item, we replace the item with a freshly created instance based on Nexo's current item
 * configuration.
 * <li>As a safety net, whenever a trade would give an outdated Nexo item to the trading player, we
 * replace the result item with a freshly created instance.
 * <li>When a player shopkeeper checks its containers for the items it has in stock, we identify
 * Nexo items by their id instead of comparing them strictly (see
 * {@link #stockPredicate(UnmodifiableItemStack)}).
 * </ul>
 */
public class NexoIntegration {

	// Set while the integration is active. Static, so that the shopkeeper implementations can
	// consult the matcher without having to reach through the plugin instance.
	private static volatile @Nullable NexoItemMatcher matcher = null;

	/**
	 * Gets a {@link Predicate} that accepts the items that count as stock for the given offer item.
	 * <p>
	 * Unless the Nexo integration is active and the given item is a Nexo item, this falls back to
	 * Shopkeepers' normal strict item comparison. Both comparisons ignore the
	 * {@link ItemIdentityTags item identity tags} if they are ignored.
	 *
	 * @param offerItem
	 *            the offer item, not <code>null</code>
	 * @return the Predicate, not <code>null</code>
	 */
	public static Predicate<@ReadOnly @Nullable ItemStack> stockPredicate(
			UnmodifiableItemStack offerItem
	) {
		Validate.notNull(offerItem, "offerItem is null");
		return ItemIdentityTags.ignoringTags(
				offerItem,
				NexoIntegration::nexoStockPredicate
		);
	}

	private static Predicate<@ReadOnly @Nullable ItemStack> nexoStockPredicate(
			UnmodifiableItemStack offerItem
	) {
		NexoItemMatcher matcher = NexoIntegration.matcher;
		// The setting is checked here, rather than when the matcher is set up, so that it can be
		// toggled via a config reload without requiring a server restart:
		if (matcher == null || !Settings.nexoMatchStockById) {
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
	private @Nullable NexoListener listener = null;

	public NexoIntegration(SKShopkeepersPlugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		this.plugin = plugin;
	}

	public void onEnable() {
		if (!Settings.enableNexoIntegration) return;
		Plugin nexoPlugin = NexoDependency.getPlugin();
		if (nexoPlugin == null) return;

		NexoDependency.Api nexoApi;
		try {
			nexoApi = NexoDependency.resolveApi(nexoPlugin);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.warning("Nexo found, but its API does not match the expected API (version "
					+ nexoPlugin.getDescription().getVersion() + "). Stored Nexo items are not "
					+ "updated automatically.", e);
			return;
		}

		Log.info("Nexo found: Automatically updating stored Nexo items.");
		NexoListener listener = new NexoListener(plugin, nexoApi);
		this.listener = listener;
		EventUtils.registerEvents(listener, plugin);
		// Nexo's items loaded event is only known at runtime:
		Bukkit.getPluginManager().registerEvent(
				nexoApi.getItemsLoadedEventClass(),
				listener,
				EventPriority.MONITOR,
				EventUtils.lockedExecutor((eventListener, event) -> listener.onNexoItemsLoaded()),
				plugin,
				false
		);
		matcher = new NexoItemMatcher();

		// Nexo might have already loaded its items before this plugin got enabled:
		listener.onNexoItemsLoaded();
	}

	public void onDisable() {
		matcher = null;
		NexoListener listener = this.listener;
		if (listener != null) {
			HandlerList.unregisterAll(listener);
			this.listener = null;
		}
	}
}
