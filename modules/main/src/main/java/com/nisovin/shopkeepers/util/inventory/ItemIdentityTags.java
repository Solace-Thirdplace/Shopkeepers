package com.nisovin.shopkeepers.util.inventory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.compat.Compat;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.util.annotations.ReadOnly;
import com.nisovin.shopkeepers.util.bukkit.NamespacedKeyUtils;
import com.nisovin.shopkeepers.util.bukkit.RegistryUtils;
import com.nisovin.shopkeepers.util.bukkit.ServerUtils;
import com.nisovin.shopkeepers.util.java.Validate;

/**
 * Item comparisons that ignore the per-item identity tags that ThirdPlaceMcTweaks writes on
 * unstackable items.
 * <p>
 * Every physical item carries its own identity tags, so two otherwise identical items are not
 * {@link ItemStack#isSimilar(ItemStack) similar}. The comparisons in here strip these tags from
 * copies of both items before they compare them, and otherwise keep the semantics of the
 * comparison they wrap.
 * <p>
 * The tags are only ignored while {@link Settings#ignoreItemIdentityTags} is enabled. Otherwise,
 * all comparisons are strict.
 */
public final class ItemIdentityTags {

	private static final String NAMESPACE = "thirdplacetweaks";

	/**
	 * The key of Minecraft's custom data item component.
	 */
	public static final String CUSTOM_DATA_COMPONENT = "minecraft:custom_data";

	/**
	 * The identity tag keys.
	 */
	public static final List<NamespacedKey> KEYS = List.of(
			NamespacedKeyUtils.create(NAMESPACE, "iid"),
			NamespacedKeyUtils.create(NAMESPACE, "origin"),
			NamespacedKeyUtils.create(NAMESPACE, "ts"),
			NamespacedKeyUtils.create(NAMESPACE, "by")
	);

	/**
	 * Checks whether item comparisons ignore the identity tags.
	 * 
	 * @return <code>true</code> if the identity tags are ignored
	 */
	public static boolean isIgnored() {
		return Settings.ignoreItemIdentityTags;
	}

	/**
	 * Checks whether the given item carries any of the identity tags.
	 * 
	 * @param itemStack
	 *            the item, can be <code>null</code>
	 * @return <code>true</code> if the item carries identity tags
	 */
	public static boolean hasTags(@ReadOnly @Nullable ItemStack itemStack) {
		if (itemStack == null || !itemStack.hasItemMeta()) return false;
		Boolean hasTags = hasTagsWithoutItemMeta(itemStack);
		if (hasTags != null) return hasTags;

		ItemMeta itemMeta = itemStack.getItemMeta();
		if (itemMeta == null) return false;
		return hasTags(itemMeta.getPersistentDataContainer());
	}

	/**
	 * Checks whether the given item carries any of the identity tags.
	 * 
	 * @param itemStack
	 *            the item, can be <code>null</code>
	 * @return <code>true</code> if the item carries identity tags
	 */
	public static boolean hasTags(@Nullable UnmodifiableItemStack itemStack) {
		return hasTags(ItemUtils.asItemStackOrNull(itemStack));
	}

	// Paper provides a read-only view on an item's custom tags that does not copy the item meta.
	// Null if not available.
	private static final @Nullable MethodHandle GET_TAGS_VIEW;
	private static final @Nullable MethodHandle TAGS_VIEW_HAS;

	static {
		MethodHandle getTagsView = null;
		MethodHandle tagsViewHas = null;
		try {
			Method getter = ItemStack.class.getMethod("getPersistentDataContainer");
			Method has = getter.getReturnType().getMethod("has", NamespacedKey.class);
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			getTagsView = lookup.unreflect(getter).asType(
					MethodType.methodType(Object.class, ItemStack.class)
			);
			tagsViewHas = lookup.unreflect(has).asType(
					MethodType.methodType(boolean.class, Object.class, NamespacedKey.class)
			);
		} catch (Exception e) {
			// Not running on Paper:
			getTagsView = null;
			tagsViewHas = null;
		}
		GET_TAGS_VIEW = getTagsView;
		TAGS_VIEW_HAS = tagsViewHas;
	}

	/**
	 * Checks whether the given item carries any of the identity tags, without copying its item
	 * meta.
	 * 
	 * @param itemStack
	 *            the item, not <code>null</code>
	 * @return whether the item carries identity tags, or <code>null</code> if this cannot be
	 *         checked without copying the item meta
	 */
	private static @Nullable Boolean hasTagsWithoutItemMeta(@ReadOnly ItemStack itemStack) {
		MethodHandle getTagsView = GET_TAGS_VIEW;
		MethodHandle tagsViewHas = TAGS_VIEW_HAS;
		if (getTagsView == null || tagsViewHas == null) return null;
		try {
			Object tags = (Object) getTagsView.invokeExact(itemStack);
			for (NamespacedKey key : KEYS) {
				if ((boolean) tagsViewHas.invokeExact(tags, key)) return true;
			}
			return false;
		} catch (Throwable e) {
			// Fall back to the item meta:
			return null;
		}
	}

	private static boolean hasTags(PersistentDataContainer tags) {
		if (tags.isEmpty()) return false;
		for (NamespacedKey key : KEYS) {
			if (tags.has(key)) return true;
		}
		return false;
	}

	/**
	 * Gets the given item without its identity tags.
	 * <p>
	 * This does not check whether the identity tags are {@link #isIgnored() ignored}.
	 * 
	 * @param itemStack
	 *            the item, can be <code>null</code>
	 * @return the given item itself if it carries no identity tags, otherwise a copy of it without
	 *         the identity tags
	 */
	public static @Nullable ItemStack strip(@ReadOnly @Nullable ItemStack itemStack) {
		if (itemStack == null || !itemStack.hasItemMeta()) return itemStack;
		// Avoids copying the item meta of items without identity tags, if possible:
		Boolean hasTags = hasTagsWithoutItemMeta(itemStack);
		if (hasTags != null && !hasTags) return itemStack;

		ItemMeta itemMeta = itemStack.getItemMeta();
		if (itemMeta == null) return itemStack;
		PersistentDataContainer tags = itemMeta.getPersistentDataContainer();
		if (hasTags == null && !hasTags(tags)) return itemStack;

		// The item meta is a copy, so this does not modify the given item:
		for (NamespacedKey key : KEYS) {
			tags.remove(key);
		}
		ItemStack stripped = itemStack.clone();
		stripped.setItemMeta(itemMeta);
		return stripped;
	}

	/**
	 * Gets the given item without its identity tags.
	 * <p>
	 * This does not check whether the identity tags are {@link #isIgnored() ignored}.
	 * 
	 * @param itemStack
	 *            the item, can be <code>null</code>
	 * @return the given item itself if it carries no identity tags, otherwise a copy of it without
	 *         the identity tags
	 */
	public static @Nullable UnmodifiableItemStack strip(@Nullable UnmodifiableItemStack itemStack) {
		if (itemStack == null) return null;
		ItemStack delegate = ItemUtils.asItemStack(itemStack);
		ItemStack stripped = strip(delegate);
		if (stripped == delegate) return itemStack;
		return UnmodifiableItemStack.of(stripped);
	}

	/**
	 * Checks if the given offered item fulfills the requirements of a trading recipe that requires
	 * the given item, according to Minecraft's item comparison rules, but ignores the identity tags
	 * of both items if they are {@link #isIgnored() ignored}.
	 * <p>
	 * Unlike Minecraft's comparison of the offered items with the
	 * {@link #toMerchantCostItem(ItemStack) merchant cost items}, this still compares all other
	 * custom data.
	 * 
	 * @param offeredItem
	 *            the offered item, can be <code>null</code>
	 * @param requiredItem
	 *            the required item, can be <code>null</code>
	 * @return <code>true</code> if the offered item matches the required item
	 */
	public static boolean matchesRequiredItem(
			@ReadOnly @Nullable ItemStack offeredItem,
			@Nullable UnmodifiableItemStack requiredItem
	) {
		if (isIgnored()) {
			offeredItem = strip(offeredItem);
			requiredItem = strip(requiredItem);
		}
		return Compat.getProvider().matches(offeredItem, requiredItem);
	}

	/**
	 * Gets the item that a merchant recipe shall require in place of the given required item.
	 * <p>
	 * Minecraft compares the offered items with the required items of a merchant recipe itself, and
	 * compares their custom data as a whole. If the identity tags are {@link #isIgnored() ignored},
	 * this therefore removes the identity tags, and removes the custom data component entirely from
	 * unstackable items, because these are the items that carry identity tags. The caller is
	 * responsible for comparing the offered items with the full required items again.
	 * 
	 * @param itemStack
	 *            the required item, can be <code>null</code>
	 * @return the given item itself if it remains unchanged, otherwise a modified copy of it
	 */
	public static @Nullable ItemStack toMerchantCostItem(@ReadOnly @Nullable ItemStack itemStack) {
		if (!isIgnored()) return itemStack;
		ItemStack costItem = strip(itemStack);
		if (costItem == null || ItemUtils.isEmpty(costItem)) return costItem;
		if (costItem.getMaxStackSize() != 1) return costItem;
		return withoutCustomData(costItem);
	}

	/**
	 * Gets the given item without its {@value #CUSTOM_DATA_COMPONENT} component.
	 * <p>
	 * This also removes the custom data that is not accessible via the Bukkit API.
	 * 
	 * @param itemStack
	 *            the item, not <code>null</code>
	 * @return the given item itself if it has no custom data, otherwise a copy of it without its
	 *         custom data
	 */
	public static ItemStack withoutCustomData(@ReadOnly ItemStack itemStack) {
		Validate.notNull(itemStack, "itemStack is null");
		if (ItemUtils.isEmpty(itemStack)) return itemStack;
		ItemStackComponentsData componentsData = ItemStackComponentsData.of(itemStack);
		if (componentsData == null || !componentsData.contains(CUSTOM_DATA_COMPONENT)) {
			return itemStack;
		}

		componentsData.remove(CUSTOM_DATA_COMPONENT);
		return Compat.getProvider().deserializeItemStack(
				ServerUtils.getDataVersion(),
				RegistryUtils.getKeyOrThrow(itemStack.getType()),
				itemStack.getAmount(),
				componentsData
		);
	}

	/**
	 * Same as {@link ItemUtils#isSimilar(ItemStack, ItemStack)}, but ignores the identity tags if
	 * they are {@link #isIgnored() ignored}.
	 * 
	 * @param item1
	 *            an item stack
	 * @param item2
	 *            another item stack
	 * @return <code>true</code> if the item stacks are similar, or both <code>null</code>
	 */
	public static boolean isSimilar(
			@ReadOnly @Nullable ItemStack item1,
			@ReadOnly @Nullable ItemStack item2
	) {
		if (ItemUtils.isSimilar(item1, item2)) return true;
		if (!isIgnored()) return false;
		if (item1 == null || item2 == null) return false;
		if (item1.getType() != item2.getType()) return false;

		ItemStack strippedItem1 = strip(item1);
		ItemStack strippedItem2 = strip(item2);
		// Skip the second comparison if neither item carried any identity tags:
		if (strippedItem1 == item1 && strippedItem2 == item2) return false;
		return ItemUtils.isSimilar(strippedItem1, strippedItem2);
	}

	/**
	 * Same as {@link #isSimilar(ItemStack, ItemStack)}, but for {@link UnmodifiableItemStack}s.
	 * 
	 * @param item1
	 *            an item stack
	 * @param item2
	 *            another item stack
	 * @return <code>true</code> if the item stacks are similar, or both <code>null</code>
	 */
	public static boolean isSimilar(
			@Nullable UnmodifiableItemStack item1,
			@ReadOnly @Nullable ItemStack item2
	) {
		return isSimilar(ItemUtils.asItemStackOrNull(item1), item2);
	}

	/**
	 * Same as {@link #isSimilar(ItemStack, ItemStack)}, but for {@link UnmodifiableItemStack}s.
	 * 
	 * @param item1
	 *            an item stack
	 * @param item2
	 *            another item stack
	 * @return <code>true</code> if the item stacks are similar, or both <code>null</code>
	 */
	public static boolean isSimilar(
			@Nullable UnmodifiableItemStack item1,
			@Nullable UnmodifiableItemStack item2
	) {
		return isSimilar(
				ItemUtils.asItemStackOrNull(item1),
				ItemUtils.asItemStackOrNull(item2)
		);
	}

	/**
	 * Checks whether the given items are equal, including their stack sizes, but ignores the
	 * identity tags if they are {@link #isIgnored() ignored}.
	 * 
	 * @param item1
	 *            an item stack
	 * @param item2
	 *            another item stack
	 * @return <code>true</code> if the item stacks are equal, or both <code>null</code>
	 */
	public static boolean isEqual(
			@Nullable UnmodifiableItemStack item1,
			@Nullable UnmodifiableItemStack item2
	) {
		if (item1 == null) return (item2 == null);
		if (item1.equals(item2)) return true;
		if (item2 == null) return false;
		return item1.getAmount() == item2.getAmount() && isSimilar(item1, item2);
	}

	/**
	 * Gets a {@link Predicate} that accepts the items that are {@link #isSimilar(ItemStack,
	 * ItemStack) similar} to the given item.
	 * 
	 * @param itemStack
	 *            the item stack, not <code>null</code>
	 * @return the Predicate, not <code>null</code>
	 */
	public static Predicate<@ReadOnly @Nullable ItemStack> similarItems(
			UnmodifiableItemStack itemStack
	) {
		return ignoringTags(itemStack, ItemUtils::similarItems);
	}

	/**
	 * Gets a {@link Predicate} that accepts the items that are {@link #isSimilar(ItemStack,
	 * ItemStack) similar} to the given item.
	 * 
	 * @param itemStack
	 *            the item stack, not <code>null</code>
	 * @return the Predicate, not <code>null</code>
	 */
	public static Predicate<@ReadOnly @Nullable ItemStack> similarItems(
			@ReadOnly ItemStack itemStack
	) {
		Validate.notNull(itemStack, "itemStack is null");
		return similarItems(UnmodifiableItemStack.ofNonNull(itemStack));
	}

	/**
	 * Wraps the item comparison of the given {@link Predicate} factory so that it ignores the
	 * identity tags if they are {@link #isIgnored() ignored}.
	 * <p>
	 * The returned Predicate accepts an item if the Predicate created for the given item accepts
	 * it as is, or if the Predicate created for the given item without its identity tags accepts
	 * the item without its identity tags. The factory has to reject items of a different type.
	 * 
	 * @param itemStack
	 *            the item stack to compare with, not <code>null</code>
	 * @param predicateFactory
	 *            creates the wrapped Predicate for a given item stack, not <code>null</code>
	 * @return the Predicate, not <code>null</code>
	 */
	public static Predicate<@ReadOnly @Nullable ItemStack> ignoringTags(
			UnmodifiableItemStack itemStack,
			Function<UnmodifiableItemStack, Predicate<@ReadOnly @Nullable ItemStack>>
					predicateFactory
	) {
		Validate.notNull(itemStack, "itemStack is null");
		Validate.notNull(predicateFactory, "predicateFactory is null");
		Predicate<@ReadOnly @Nullable ItemStack> strict = predicateFactory.apply(itemStack);
		if (!isIgnored()) return strict;

		UnmodifiableItemStack strippedItemStack = Unsafe.assertNonNull(strip(itemStack));
		Predicate<@ReadOnly @Nullable ItemStack> stripped = (strippedItemStack == itemStack)
				? strict
				: predicateFactory.apply(strippedItemStack);
		Material type = itemStack.getType();
		return (otherItemStack) -> {
			if (strict.test(otherItemStack)) return true;
			if (otherItemStack == null || otherItemStack.getType() != type) return false;

			ItemStack strippedOtherItemStack = strip(otherItemStack);
			// Skip the second comparison if neither item carried any identity tags:
			if (strippedOtherItemStack == otherItemStack && stripped == strict) return false;
			return stripped.test(strippedOtherItemStack);
		};
	}

	/**
	 * Finds the stock item that a trade removes from the given contents and that the trading
	 * player shall receive in place of the given result item.
	 * <p>
	 * The trading player receives the physical stock item, so that it keeps its own identity tags.
	 * This only applies to single items, because only unstackable items carry identity tags. This
	 * applies regardless of whether the identity tags are {@link #isIgnored() ignored}.
	 * 
	 * @param contentsList
	 *            the stock contents, in the order in which the trade removes items from them, not
	 *            <code>null</code>
	 * @param stockMatcher
	 *            the Predicate that the trade uses to remove the stock items, not <code>null</code>
	 * @param resultItem
	 *            the result item of the trading recipe, not <code>null</code>
	 * @return a copy of the stock item, or <code>null</code> if the player shall receive the
	 *         result item as is
	 */
	public static @Nullable UnmodifiableItemStack findTradedStockItem(
			@ReadOnly List<@ReadOnly @Nullable ItemStack @ReadOnly []> contentsList,
			Predicate<@ReadOnly ? super ItemStack> stockMatcher,
			UnmodifiableItemStack resultItem
	) {
		Validate.notNull(contentsList, "contentsList is null");
		Validate.notNull(stockMatcher, "stockMatcher is null");
		Validate.notNull(resultItem, "resultItem is null");
		if (resultItem.getAmount() != 1) return null;

		for (var contents : contentsList) {
			for (ItemStack stockItem : contents) {
				if (stockItem == null) continue;
				if (ItemUtils.isEmpty(stockItem)) continue;
				if (!stockMatcher.test(stockItem)) continue;

				// This is the stock item that the trade removes:
				if (!hasTags(stockItem) && !hasTags(resultItem)) return null;
				if (resultItem.isSimilar(stockItem)) return null;
				return UnmodifiableItemStack.ofNonNull(ItemUtils.copySingleItem(stockItem));
			}
		}
		return null;
	}

	private ItemIdentityTags() {
	}
}
