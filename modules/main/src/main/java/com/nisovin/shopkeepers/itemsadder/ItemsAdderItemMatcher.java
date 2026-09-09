package com.nisovin.shopkeepers.itemsadder;

import java.util.function.Predicate;

import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.util.annotations.ReadOnly;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;
import com.nisovin.shopkeepers.util.java.Validate;

import dev.lone.itemsadder.api.CustomStack;

/**
 * Matches the ItemsAdder items that a player shopkeeper has in stock against the items of its
 * offers.
 * <p>
 * Shopkeepers normally determines a shopkeeper's stock by looking for items in its containers that
 * are {@link ItemStack#isSimilar(ItemStack) similar} to the offer's item. For ItemsAdder items that
 * comparison is not stable over time: ItemsAdder recreates its items whenever it (re)loads its item
 * configurations, and our {@link ItemsAdderIntegration} rewrites the stored offer items to match.
 * The item stacks that are physically sitting inside the shopkeeper's containers are not rewritten
 * along with them, so they eventually stop being similar to the offer item and the shopkeeper
 * reports itself as out of stock even though the container is full.
 * <p>
 * This matcher instead identifies ItemsAdder items by their namespaced id and only compares the
 * data that the ItemsAdder item configuration does not define itself. See
 * {@link ItemsAdderStockMatching#customExtrasMatch(org.bukkit.inventory.meta.ItemMeta,
 * org.bukkit.inventory.meta.ItemMeta)}.
 * <p>
 * Items that are excluded from the automatic item updates keep the strict comparison: Their stored
 * copies are intentionally different from their item configuration, so loosening the comparison
 * would defeat the exclusion.
 * <p>
 * This class references ItemsAdder API classes and shall therefore only be instantiated when the
 * ItemsAdder plugin is present.
 */
class ItemsAdderItemMatcher {

	ItemsAdderItemMatcher() {
	}

	/**
	 * Gets a {@link Predicate} that accepts the items that count as stock for the given offer item.
	 *
	 * @param offerItem
	 *            the offer item, not <code>null</code>
	 * @return the Predicate, not <code>null</code>
	 */
	Predicate<@ReadOnly @Nullable ItemStack> stockPredicate(UnmodifiableItemStack offerItem) {
		Validate.notNull(offerItem, "offerItem is null");

		// Resolved once per offer, rather than once per inspected container slot:
		String offerItemId = this.getItemId(offerItem.copy());
		if (offerItemId == null || ItemsAdderListener.isExcluded(offerItemId)) {
			// Not an ItemsAdder item, or intentionally kept as-is: Compare strictly, as before.
			return ItemUtils.similarItems(offerItem);
		}

		ItemStack offerItemCopy = offerItem.copy();
		return (containerItem) -> {
			if (ItemUtils.isEmpty(containerItem)) return false;
			assert containerItem != null;
			// Both an early-out for the common case and the only comparison that is performed for
			// items that ItemsAdder cannot identify:
			if (offerItemCopy.isSimilar(containerItem)) return true;
			if (offerItemCopy.getType() != containerItem.getType()) return false;

			String containerItemId = this.getItemId(containerItem.clone());
			if (!offerItemId.equals(containerItemId)) return false;

			return ItemsAdderStockMatching.customExtrasMatch(
					offerItemCopy.getItemMeta(),
					containerItem.getItemMeta()
			);
		};
	}

	/**
	 * Gets the ItemsAdder namespaced id of the given item.
	 *
	 * @param item
	 *            a private copy of the item to identify, not <code>null</code>
	 * @return the namespaced id, or <code>null</code> if ItemsAdder does not identify the item as
	 *         one of its own
	 */
	private @Nullable String getItemId(ItemStack item) {
		// Note: As in ItemsAdderListener#recreateItem, this intentionally only detects items that
		// ItemsAdder itself can positively identify. Guessing the identity of legacy untagged items
		// based on their custom model data is not safe.
		CustomStack customStack = CustomStack.byItemStack(item);
		if (customStack == null) return null;
		return customStack.getNamespacedID();
	}
}
