package com.nisovin.shopkeepers.dependencies.nexo;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.util.java.Validate;

/**
 * Access to the Nexo plugin.
 * <p>
 * Nexo is a Paper plugin written in Kotlin. Instead of compiling against its API, the few API
 * members we need are resolved reflectively through Nexo's own class loader.
 */
public final class NexoDependency {

	public static final String PLUGIN_NAME = "Nexo";

	private static final String NEXO_ITEMS_CLASS = "com.nexomc.nexo.api.NexoItems";
	private static final String ITEM_BUILDER_CLASS = "com.nexomc.nexo.items.ItemBuilder";
	private static final String ITEMS_LOADED_EVENT_CLASS
			= "com.nexomc.nexo.api.events.NexoItemsLoadedEvent";

	public static @Nullable Plugin getPlugin() {
		return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
	}

	public static boolean isPluginEnabled() {
		return Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
	}

	/**
	 * The resolved Nexo API.
	 */
	public static final class Api {

		private final Class<? extends Event> itemsLoadedEventClass;
		private final Method itemFromId;
		private final Method build;

		private Api(
				Class<? extends Event> itemsLoadedEventClass,
				Method itemFromId,
				Method build
		) {
			this.itemsLoadedEventClass = itemsLoadedEventClass;
			this.itemFromId = itemFromId;
			this.build = build;
		}

		/**
		 * Gets the class of the event that Nexo calls whenever it has (re)loaded its items.
		 *
		 * @return the event class
		 */
		public Class<? extends Event> getItemsLoadedEventClass() {
			return itemsLoadedEventClass;
		}

		/**
		 * Creates a new item stack of the Nexo item with the given id, based on Nexo's current
		 * item configuration.
		 *
		 * @param itemId
		 *            the Nexo item id, not <code>null</code>
		 * @return the new item stack, or <code>null</code> if there is no such Nexo item
		 */
		public @Nullable ItemStack createItem(String itemId) {
			Validate.notNull(itemId, "itemId is null");
			try {
				Object itemBuilder = itemFromId.invoke(null, itemId);
				if (itemBuilder == null) return null;
				return (ItemStack) build.invoke(itemBuilder);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Could not create Nexo item '" + itemId + "'!", e);
			}
		}
	}

	/**
	 * Resolves the Nexo API.
	 *
	 * @param nexoPlugin
	 *            the Nexo plugin, not <code>null</code>
	 * @return the resolved API
	 * @throws ReflectiveOperationException
	 *             if the API of the installed Nexo version does not match the expected API
	 */
	public static Api resolveApi(Plugin nexoPlugin) throws ReflectiveOperationException {
		Validate.notNull(nexoPlugin, "nexoPlugin is null");
		ClassLoader classLoader = nexoPlugin.getClass().getClassLoader();
		Class<?> nexoItemsClass = Class.forName(NEXO_ITEMS_CLASS, true, classLoader);
		Class<?> itemBuilderClass = Class.forName(ITEM_BUILDER_CLASS, true, classLoader);
		Class<? extends Event> itemsLoadedEventClass = Class.forName(
				ITEMS_LOADED_EVENT_CLASS,
				true,
				classLoader
		).asSubclass(Event.class);
		Method itemFromId = nexoItemsClass.getMethod("itemFromId", String.class);
		Method build = itemBuilderClass.getMethod("build");
		return new Api(itemsLoadedEventClass, itemFromId, build);
	}

	private NexoDependency() {
	}
}
