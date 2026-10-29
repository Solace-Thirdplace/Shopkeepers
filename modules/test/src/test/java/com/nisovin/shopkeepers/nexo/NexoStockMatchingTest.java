package com.nisovin.shopkeepers.nexo;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.Assert;
import org.junit.Test;

import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.testutil.AbstractBukkitTest;
import com.nisovin.shopkeepers.util.bukkit.NamespacedKeyUtils;

/**
 * Tests for {@link NexoStockMatching}, {@link NexoItemIds} and {@link NexoItemMatcher}.
 */
public class NexoStockMatchingTest extends AbstractBukkitTest {

	private static ItemMeta metaOf(ItemStack itemStack) {
		return Unsafe.assertNonNull(itemStack.getItemMeta());
	}

	private static ItemStack named(Material type, String displayName) {
		ItemStack itemStack = new ItemStack(type);
		ItemMeta itemMeta = metaOf(itemStack);
		itemMeta.setItemName(displayName);
		itemStack.setItemMeta(itemMeta);
		return itemStack;
	}

	private static boolean extrasMatch(ItemStack itemStack1, ItemStack itemStack2) {
		return NexoStockMatching.customExtrasMatch(
				itemStack1.getItemMeta(),
				itemStack2.getItemMeta()
		);
	}

	// CUSTOM EXTRAS

	@Test
	public void testDisplayDataIsIgnored() {
		// An outdated copy of a Nexo item differs from the current one in its display data.
		// This is exactly the difference that the matching is meant to look past.
		ItemStack oldGeneration = named(Material.PAPER, "arkadelaide_glorious_birch_lake");
		ItemStack newGeneration = named(Material.PAPER, "Glorious Birch Lake");
		Assert.assertFalse("Precondition", oldGeneration.isSimilar(newGeneration));
		Assert.assertTrue(extrasMatch(oldGeneration, newGeneration));
	}

	@Test
	public void testEnchantmentsAreCompared() {
		ItemStack plain = new ItemStack(Material.DIAMOND_SWORD);
		ItemStack enchanted = new ItemStack(Material.DIAMOND_SWORD);
		ItemMeta enchantedMeta = metaOf(enchanted);
		enchantedMeta.addEnchant(Enchantment.SHARPNESS, 2, true);
		enchanted.setItemMeta(enchantedMeta);

		Assert.assertFalse(extrasMatch(plain, enchanted));
		Assert.assertTrue(extrasMatch(enchanted, enchanted.clone()));
	}

	@Test
	public void testStoredEnchantmentsAreCompared() {
		ItemStack book1 = new ItemStack(Material.ENCHANTED_BOOK);
		EnchantmentStorageMeta meta1 = (EnchantmentStorageMeta) metaOf(book1);
		meta1.addStoredEnchant(Enchantment.SHARPNESS, 1, true);
		book1.setItemMeta(meta1);

		ItemStack book2 = new ItemStack(Material.ENCHANTED_BOOK);
		EnchantmentStorageMeta meta2 = (EnchantmentStorageMeta) metaOf(book2);
		meta2.addStoredEnchant(Enchantment.SHARPNESS, 3, true);
		book2.setItemMeta(meta2);

		Assert.assertFalse(extrasMatch(book1, book2));
		Assert.assertTrue(extrasMatch(book1, book1.clone()));
	}

	@Test
	public void testDamageIsCompared() {
		ItemStack undamaged = new ItemStack(Material.DIAMOND_SWORD);
		ItemStack damaged = new ItemStack(Material.DIAMOND_SWORD);
		Damageable damagedMeta = (Damageable) metaOf(damaged);
		damagedMeta.setDamage(50);
		damaged.setItemMeta(damagedMeta);

		Assert.assertFalse(extrasMatch(undamaged, damaged));
	}

	@Test
	public void testUnbreakableIsCompared() {
		ItemStack breakable = new ItemStack(Material.DIAMOND_SWORD);
		ItemStack unbreakable = new ItemStack(Material.DIAMOND_SWORD);
		ItemMeta unbreakableMeta = metaOf(unbreakable);
		unbreakableMeta.setUnbreakable(true);
		unbreakable.setItemMeta(unbreakableMeta);

		Assert.assertFalse(extrasMatch(breakable, unbreakable));
	}

	@Test
	public void testForeignCustomTagsAreCompared() {
		ItemStack withTag = new ItemStack(Material.PAPER);
		ItemMeta withTagMeta = metaOf(withTag);
		withTagMeta.getPersistentDataContainer().set(
				NamespacedKeyUtils.create("cmilib", "nbtcommands"),
				PersistentDataType.STRING,
				"say hi"
		);
		withTag.setItemMeta(withTagMeta);

		ItemStack withoutTag = new ItemStack(Material.PAPER);
		Assert.assertFalse(extrasMatch(withTag, withoutTag));
		Assert.assertTrue(extrasMatch(withTag, withTag.clone()));
	}

	@Test
	public void testNexoCustomTagsAreIgnored() {
		// Nexo is free to change its own tags between versions. They identify the item, which the
		// caller has already verified by the time this comparison runs.
		ItemStack itemStack1 = new ItemStack(Material.PAPER);
		ItemMeta meta1 = metaOf(itemStack1);
		meta1.getPersistentDataContainer().set(
				NamespacedKeyUtils.create("nexo", "some-internal-state"),
				PersistentDataType.STRING,
				"one"
		);
		itemStack1.setItemMeta(meta1);

		ItemStack itemStack2 = new ItemStack(Material.PAPER);
		ItemMeta meta2 = metaOf(itemStack2);
		meta2.getPersistentDataContainer().set(
				NamespacedKeyUtils.create("nexo", "some-internal-state"),
				PersistentDataType.STRING,
				"two"
		);
		itemStack2.setItemMeta(meta2);

		Assert.assertFalse("Precondition", itemStack1.isSimilar(itemStack2));
		Assert.assertTrue(extrasMatch(itemStack1, itemStack2));
	}

	// ATTRIBUTE MODIFIER IDS

	private static ItemStack withArmorModifier(String modifierId, double amount) {
		ItemStack itemStack = new ItemStack(Material.PAPER);
		ItemMeta itemMeta = metaOf(itemStack);
		itemMeta.addAttributeModifier(
				Attribute.ARMOR,
				new AttributeModifier(
						NamespacedKeyUtils.create("minecraft", modifierId),
						amount,
						Operation.ADD_NUMBER,
						EquipmentSlotGroup.ARMOR
				)
		);
		itemStack.setItemMeta(itemMeta);
		return itemStack;
	}

	@Test
	public void testModifierIdsOnlyDifference() {
		// A freshly built item can carry a fresh modifier id, so a stored item would never equal a
		// freshly created copy of itself.
		ItemStack stored = withArmorModifier("7384e0d3-4a5e-465d-bf2e-41943f7f62e5", 0.0D);
		ItemStack fresh = withArmorModifier("5da6e22e-5c53-4514-8e7b-206d5cd568c5", 0.0D);
		Assert.assertFalse("Precondition", stored.equals(fresh));

		ItemMeta storedMeta = metaOf(stored);
		ItemMeta freshMeta = metaOf(fresh);
		Assert.assertTrue(NexoStockMatching.copyAttributeModifierIds(storedMeta, freshMeta));

		fresh.setItemMeta(freshMeta);
		Assert.assertEquals(stored, fresh);
	}

	@Test
	public void testModifierEffectDifferenceIsKept() {
		ItemStack stored = withArmorModifier("7384e0d3-4a5e-465d-bf2e-41943f7f62e5", 0.0D);
		ItemStack fresh = withArmorModifier("5da6e22e-5c53-4514-8e7b-206d5cd568c5", 2.0D);

		Assert.assertFalse(NexoStockMatching.copyAttributeModifierIds(
				metaOf(stored),
				metaOf(fresh)
		));
	}

	@Test
	public void testModifiersOnOnlyOneItem() {
		ItemStack withModifier = withArmorModifier("7384e0d3-4a5e-465d-bf2e-41943f7f62e5", 0.0D);
		ItemStack withoutModifier = new ItemStack(Material.PAPER);

		Assert.assertFalse(NexoStockMatching.copyAttributeModifierIds(
				metaOf(withModifier),
				metaOf(withoutModifier)
		));
		Assert.assertFalse(NexoStockMatching.copyAttributeModifierIds(
				metaOf(withoutModifier),
				metaOf(withModifier)
		));
	}

	@Test
	public void testNoModifiersOnEitherItem() {
		ItemStack itemStack1 = new ItemStack(Material.PAPER);
		ItemStack itemStack2 = new ItemStack(Material.PAPER);

		Assert.assertTrue(NexoStockMatching.copyAttributeModifierIds(
				metaOf(itemStack1),
				metaOf(itemStack2)
		));
	}

	// NEXO ITEM IDS

	private static ItemStack nexoItem(Material type, String itemId, String displayName) {
		ItemStack itemStack = named(type, displayName);
		ItemMeta itemMeta = metaOf(itemStack);
		itemMeta.getPersistentDataContainer().set(
				NexoItemIds.ITEM_ID_KEY,
				PersistentDataType.STRING,
				itemId
		);
		itemStack.setItemMeta(itemMeta);
		return itemStack;
	}

	@Test
	public void testItemId() {
		Assert.assertEquals(
				"glorious_birch_lake",
				NexoItemIds.getItemId(nexoItem(Material.PAPER, "glorious_birch_lake", "Lake"))
		);
		Assert.assertNull(NexoItemIds.getItemId(new ItemStack(Material.PAPER)));
		Assert.assertNull(NexoItemIds.getItemId(named(Material.PAPER, "Lake")));
		Assert.assertNull(NexoItemIds.getItemId(null));
	}

	private static boolean matchesStock(ItemStack offerItem, ItemStack containerItem) {
		return new NexoItemMatcher()
				.stockPredicate(UnmodifiableItemStack.ofNonNull(offerItem))
				.test(containerItem);
	}

	@Test
	public void testStockMatchesById() {
		ItemStack offer = nexoItem(Material.PAPER, "glorious_birch_lake", "Glorious Birch Lake");
		ItemStack oldCopy = nexoItem(Material.PAPER, "glorious_birch_lake", "Old Name");
		ItemStack otherItem = nexoItem(Material.PAPER, "other_painting", "Glorious Birch Lake");
		ItemStack lookAlike = named(Material.PAPER, "Glorious Birch Lake");

		Assert.assertTrue(matchesStock(offer, offer.clone()));
		Assert.assertTrue(matchesStock(offer, oldCopy));
		Assert.assertFalse(matchesStock(offer, otherItem));
		Assert.assertFalse(matchesStock(offer, lookAlike));
	}

	@Test
	public void testExcludedItemsAreComparedStrictly() {
		ItemStack offer = nexoItem(Material.PAPER, "instant_villager_pair", "Villager Pair");
		ItemStack oldCopy = nexoItem(Material.PAPER, "instant_villager_pair", "Old Name");
		Settings.nexoItemUpdateExclusions = new ArrayList<>(List.of("Instant_Villager_Pair"));
		try {
			Assert.assertTrue(matchesStock(offer, offer.clone()));
			Assert.assertFalse(matchesStock(offer, oldCopy));
		} finally {
			Settings.nexoItemUpdateExclusions = new ArrayList<>(0);
		}
	}
}
