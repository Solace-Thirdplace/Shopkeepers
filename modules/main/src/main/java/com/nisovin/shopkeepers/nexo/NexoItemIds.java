package com.nisovin.shopkeepers.nexo;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.util.annotations.ReadOnly;
import com.nisovin.shopkeepers.util.bukkit.NamespacedKeyUtils;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;

/**
 * Identifies Nexo items.
 * <p>
 * Nexo stores the id of its items in the custom tag {@code nexo:id} (inside
 * {@code PublicBukkitValues}). Reading it does not require the Nexo plugin.
 */
public final class NexoItemIds {

	/**
	 * The namespace that Nexo uses for its own custom tags.
	 */
	public static final String NEXO_NAMESPACE = "nexo";

	/**
	 * The key of the custom tag that holds the id of a Nexo item.
	 */
	public static final NamespacedKey ITEM_ID_KEY = NamespacedKeyUtils.create(NEXO_NAMESPACE, "id");

	/**
	 * Gets the Nexo item id of the given item.
	 *
	 * @param itemStack
	 *            the item, can be <code>null</code>
	 * @return the Nexo item id, or <code>null</code> if the item is not a Nexo item
	 */
	public static @Nullable String getItemId(@ReadOnly @Nullable ItemStack itemStack) {
		if (ItemUtils.isEmpty(itemStack)) return null;
		assert itemStack != null;
		if (!itemStack.hasItemMeta()) return null;

		@Nullable ItemMeta itemMeta = itemStack.getItemMeta();
		if (itemMeta == null) return null;

		String itemId = itemMeta.getPersistentDataContainer().get(
				ITEM_ID_KEY,
				PersistentDataType.STRING
		);
		if (itemId == null || itemId.isEmpty()) return null;
		return itemId;
	}

	private NexoItemIds() {
	}
}
