package com.nisovin.shopkeepers.nexo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.google.common.collect.Multimap;

import com.nisovin.shopkeepers.api.internal.util.Unsafe;

/**
 * Item comparison helpers behind the Nexo stock matching.
 * <p>
 * These do not reference any Nexo API classes, so that they can be tested without the Nexo plugin
 * being present.
 * <p>
 * Nexo items are only loosely coupled to their item configurations, and a freshly built copy of an
 * item can differ from older copies. Two item stacks that represent the same Nexo item can
 * therefore differ in ways that are not meaningful:
 * <ul>
 * <li>Their {@link ItemMeta#getAttributeModifiers() attribute modifiers} may carry freshly
 * generated ids, even when the modifiers are otherwise identical.
 * <li>Their display related data (name, lore, item model, custom model data, and so on) reflects
 * whichever version of the item configuration was current when the item stack was created.
 * </ul>
 * Everything else is still compared: enchantments, stored enchantments, durability, the unbreakable
 * flag, and the custom tags of other plugins.
 */
public final class NexoStockMatching {

	/**
	 * Checks whether the two given items agree on everything that the Nexo item configuration
	 * does not define itself.
	 * <p>
	 * Both items are expected to have already been identified as the same Nexo item.
	 *
	 * @param itemMeta1
	 *            the meta of the first item, can be <code>null</code>
	 * @param itemMeta2
	 *            the meta of the second item, can be <code>null</code>
	 * @return <code>true</code> if the items only differ in data that Nexo defines itself
	 */
	public static boolean customExtrasMatch(
			@Nullable ItemMeta itemMeta1,
			@Nullable ItemMeta itemMeta2
	) {
		if (itemMeta1 == null || itemMeta2 == null) {
			return itemMeta1 == itemMeta2;
		}

		// Enchantments that a player added are not part of the item configuration, so an enchanted
		// copy of an item is intentionally not interchangeable with an unenchanted one:
		if (!itemMeta1.getEnchants().equals(itemMeta2.getEnchants())) return false;

		if (itemMeta1 instanceof EnchantmentStorageMeta storage1) {
			if (!(itemMeta2 instanceof EnchantmentStorageMeta storage2)) return false;
			if (!storage1.getStoredEnchants().equals(storage2.getStoredEnchants())) return false;
		} else if (itemMeta2 instanceof EnchantmentStorageMeta) {
			return false;
		}

		if (itemMeta1.isUnbreakable() != itemMeta2.isUnbreakable()) return false;

		if (itemMeta1 instanceof Damageable damageable1) {
			if (!(itemMeta2 instanceof Damageable damageable2)) return false;
			if (damageable1.getDamage() != damageable2.getDamage()) return false;
		} else if (itemMeta2 instanceof Damageable) {
			return false;
		}

		return foreignCustomTagsMatch(itemMeta1, itemMeta2);
	}

	/**
	 * Checks whether the custom tags of other plugins match between the two given item metas.
	 * <p>
	 * Nexo's own tags are ignored: They identify the item, which the caller has already verified,
	 * and Nexo is free to change them between versions.
	 *
	 * @param itemMeta1
	 *            the meta of the first item, not <code>null</code>
	 * @param itemMeta2
	 *            the meta of the second item, not <code>null</code>
	 * @return <code>true</code> if the custom tags of other plugins match
	 */
	private static boolean foreignCustomTagsMatch(ItemMeta itemMeta1, ItemMeta itemMeta2) {
		PersistentDataContainer tags1 = itemMeta1.getPersistentDataContainer();
		PersistentDataContainer tags2 = itemMeta2.getPersistentDataContainer();
		if (tags1.isEmpty() && tags2.isEmpty()) return true;

		return withoutNexoTags(itemMeta1).equals(withoutNexoTags(itemMeta2));
	}

	/**
	 * Gets a copy of the given item meta's {@link PersistentDataContainer} with Nexo's own tags
	 * removed.
	 *
	 * @param itemMeta
	 *            the item meta, not <code>null</code>
	 * @return the copied and filtered custom tags
	 */
	private static PersistentDataContainer withoutNexoTags(ItemMeta itemMeta) {
		// The meta is copied so that we do not modify the caller's item:
		ItemMeta itemMetaCopy = itemMeta.clone();
		PersistentDataContainer tags = itemMetaCopy.getPersistentDataContainer();
		// The key set is copied, because we remove entries while iterating it:
		for (NamespacedKey key : new ArrayList<>(tags.getKeys())) {
			assert key != null;
			if (key.getNamespace().equals(NexoItemIds.NEXO_NAMESPACE)) {
				tags.remove(key);
			}
		}
		return tags;
	}

	/**
	 * Checks whether the given item metas have the same attribute modifiers, ignoring the modifier
	 * ids, and if so, replaces the attribute modifiers of the second item meta with those of the
	 * first one.
	 * <p>
	 * Freshly built item copies can carry freshly generated modifier ids. Without this
	 * normalization, an item that carries an attribute modifier might never compare equal to a
	 * freshly created copy of itself, which would cause it to be needlessly rewritten on every
	 * server start.
	 *
	 * @param sourceItemMeta
	 *            the item meta to take the modifier ids from, not <code>null</code>
	 * @param targetItemMeta
	 *            the item meta to adapt, not <code>null</code>
	 * @return <code>true</code> if the modifiers only differed in their ids and the target item meta
	 *         has been adapted, <code>false</code> if the modifiers differ in some other way
	 */
	public static boolean copyAttributeModifierIds(
			ItemMeta sourceItemMeta,
			ItemMeta targetItemMeta
	) {
		@Nullable Multimap<Attribute, AttributeModifier> sourceModifiers
				= Unsafe.cast(sourceItemMeta.getAttributeModifiers());
		@Nullable Multimap<Attribute, AttributeModifier> targetModifiers
				= Unsafe.cast(targetItemMeta.getAttributeModifiers());
		if (sourceModifiers == null || targetModifiers == null) {
			// Either both items define no attribute modifiers, or only one of them does:
			return sourceModifiers == targetModifiers;
		}

		if (!sourceModifiers.keySet().equals(targetModifiers.keySet())) return false;

		// Verify that the modifiers only differ in their ids before we modify anything:
		for (Attribute attribute : targetModifiers.keySet()) {
			assert attribute != null;
			List<AttributeModifier> source = new ArrayList<>(
					Unsafe.<Collection<AttributeModifier>>cast(sourceModifiers.get(attribute))
			);
			List<AttributeModifier> target = new ArrayList<>(
					Unsafe.<Collection<AttributeModifier>>cast(targetModifiers.get(attribute))
			);
			if (source.size() != target.size()) return false;
			for (int i = 0; i < target.size(); i++) {
				if (!sameEffect(source.get(i), target.get(i))) return false;
			}
		}

		// Adopt the source item's modifier ids:
		targetItemMeta.setAttributeModifiers(null);
		for (Attribute attribute : sourceModifiers.keySet()) {
			assert attribute != null;
			Collection<AttributeModifier> modifiers
					= Unsafe.cast(sourceModifiers.get(attribute));
			for (AttributeModifier modifier : modifiers) {
				assert modifier != null;
				targetItemMeta.addAttributeModifier(attribute, modifier);
			}
		}
		return true;
	}

	/**
	 * Checks whether the two given attribute modifiers have the same effect, ignoring their ids.
	 *
	 * @param modifier1
	 *            the first modifier, not <code>null</code>
	 * @param modifier2
	 *            the second modifier, not <code>null</code>
	 * @return <code>true</code> if the modifiers only differ in their ids
	 */
	private static boolean sameEffect(AttributeModifier modifier1, AttributeModifier modifier2) {
		return modifier1.getAmount() == modifier2.getAmount()
				&& modifier1.getOperation() == modifier2.getOperation()
				&& modifier1.getSlotGroup().equals(modifier2.getSlotGroup());
	}

	private NexoStockMatching() {
	}
}
