package com.nisovin.shopkeepers.nexo;

import java.util.List;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Keeps Nexo from refusing payment with an item whose Nexo id the shop's cost doesn't carry.
 * <p>
 * Nexo cancels every click on a merchant's result slot unless each offered item has the same Nexo
 * id as the recipe's item, and has no setting for it. A shop cost saved without any id (legacy
 * Golden Coins: name and model data only) then refuses a player's tagged coin of the same look. The
 * shopkeeper's own comparison already decides whether the items pay, so inside a trading view the
 * only thing this undoes is Nexo's extra rule, and only for a cost that has no id to compare.
 */
public final class NexoTradeCosts {

	private static final int RESULT_SLOT = 2;

	/**
	 * Un-cancels a click on the result slot of a trading view if Nexo's id rule accounts for the
	 * cancellation.
	 *
	 * @param event
	 *            the click event inside a trading view, not <code>null</code>
	 */
	public static void undoNexoRefusal(InventoryClickEvent event) {
		if (!event.isCancelled() || event.getRawSlot() != RESULT_SLOT) return;
		if (!NexoIntegration.isActive()) return;
		Inventory inventory = event.getInventory();
		if (!(inventory instanceof MerchantInventory merchant)) return;
		MerchantRecipe recipe = merchant.getSelectedRecipe();
		if (recipe == null) return;

		List<ItemStack> costs = recipe.getIngredients();
		String cost1 = costs.size() > 0 ? NexoItemIds.getItemId(costs.get(0)) : null;
		String cost2 = costs.size() > 1 ? NexoItemIds.getItemId(costs.get(1)) : null;
		String offered1 = NexoItemIds.getItemId(merchant.getItem(0));
		String offered2 = NexoItemIds.getItemId(merchant.getItem(1));
		if (refusedOnlyForIdlessCost(cost1, offered1, cost2, offered2)) {
			event.setCancelled(false);
		}
	}

	/**
	 * @return <code>true</code> if Nexo refuses these ids, and each mismatch is an offered item's
	 *         id against a cost with none
	 */
	static boolean refusedOnlyForIdlessCost(
			@Nullable String cost1,
			@Nullable String offered1,
			@Nullable String cost2,
			@Nullable String offered2
	) {
		boolean differs1 = !same(cost1, offered1);
		boolean differs2 = !same(cost2, offered2);
		if (!differs1 && !differs2) return false;
		return (!differs1 || cost1 == null) && (!differs2 || cost2 == null);
	}

	private static boolean same(@Nullable String a, @Nullable String b) {
		return a == null ? b == null : a.equals(b);
	}

	private NexoTradeCosts() {
	}
}
