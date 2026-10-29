package com.nisovin.shopkeepers.nexo;

import java.util.function.Predicate;

import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.util.annotations.ReadOnly;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;
import com.nisovin.shopkeepers.util.java.Validate;

/**
 * Matches the Nexo items that a player shopkeeper has in stock against the items of its offers.
 * <p>
 * Shopkeepers normally determines a shopkeeper's stock by looking for items in its containers that
 * are {@link ItemStack#isSimilar(ItemStack) similar} to the offer's item. For Nexo items that
 * comparison is not stable over time: Our {@link NexoIntegration} rewrites the stored offer items
 * whenever Nexo (re)loads its item configurations. The item stacks that are physically sitting
 * inside the shopkeeper's containers are not rewritten along with them, so they eventually stop
 * being similar to the offer item and the shopkeeper reports itself as out of stock even though
 * the container is full.
 * <p>
 * This matcher instead identifies Nexo items by their id and only compares the data that the Nexo
 * item configuration does not define itself. See
 * {@link NexoStockMatching#customExtrasMatch(org.bukkit.inventory.meta.ItemMeta,
 * org.bukkit.inventory.meta.ItemMeta)}.
 * <p>
 * Items that are excluded from the automatic item updates keep the strict comparison: Their stored
 * copies are intentionally different from their item configuration, so loosening the comparison
 * would defeat the exclusion.
 */
class NexoItemMatcher {

	NexoItemMatcher() {
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
		ItemStack offerItemCopy = offerItem.copy();
		String offerItemId = NexoItemIds.getItemId(offerItemCopy);
		if (offerItemId == null || NexoListener.isExcluded(offerItemId)) {
			// Not a Nexo item, or intentionally kept as-is: Compare strictly, as before.
			return ItemUtils.similarItems(offerItem);
		}

		return (containerItem) -> {
			if (ItemUtils.isEmpty(containerItem)) return false;
			assert containerItem != null;
			// Both an early-out for the common case and the only comparison that is performed for
			// items that are not Nexo items:
			if (offerItemCopy.isSimilar(containerItem)) return true;
			if (offerItemCopy.getType() != containerItem.getType()) return false;

			String containerItemId = NexoItemIds.getItemId(containerItem);
			if (!offerItemId.equals(containerItemId)) return false;

			return NexoStockMatching.customExtrasMatch(
					offerItemCopy.getItemMeta(),
					containerItem.getItemMeta()
			);
		};
	}
}
