package com.nisovin.shopkeepers.nexo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.compat.Compat;
import com.nisovin.shopkeepers.config.Settings;
import com.nisovin.shopkeepers.shopkeeper.admin.AbstractAdminShopkeeper.AdminShopTradingView;
import com.nisovin.shopkeepers.testutil.AbstractBukkitTest;
import com.nisovin.shopkeepers.util.bukkit.NamespacedKeyUtils;
import com.nisovin.shopkeepers.util.inventory.InventoryUtils;
import com.nisovin.shopkeepers.util.inventory.ItemIdentityTags;
import com.nisovin.shopkeepers.util.inventory.ItemStackComponentsData;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;

/**
 * Tests for {@link ItemIdentityTags} and its composition with the stock matching of
 * {@link NexoIntegration}.
 */
public class ItemIdentityTagsTest extends AbstractBukkitTest {

	private static ItemMeta metaOf(ItemStack itemStack) {
		return Unsafe.assertNonNull(itemStack.getItemMeta());
	}

	private static ItemStack tagged(ItemStack itemStack, String iid) {
		ItemStack tagged = itemStack.clone();
		ItemMeta itemMeta = metaOf(tagged);
		PersistentDataContainer tags = itemMeta.getPersistentDataContainer();
		tags.set(
				NamespacedKeyUtils.create("thirdplacetweaks", "iid"),
				PersistentDataType.STRING,
				iid
		);
		tags.set(
				NamespacedKeyUtils.create("thirdplacetweaks", "origin"),
				PersistentDataType.STRING,
				"loot:chest"
		);
		tags.set(
				NamespacedKeyUtils.create("thirdplacetweaks", "ts"),
				PersistentDataType.LONG,
				1760000000L + iid.hashCode()
		);
		tags.set(
				NamespacedKeyUtils.create("thirdplacetweaks", "by"),
				PersistentDataType.STRING,
				UUID.randomUUID().toString()
		);
		tagged.setItemMeta(itemMeta);
		return tagged;
	}

	private static ItemStack taggedElytra() {
		return tagged(new ItemStack(Material.ELYTRA), UUID.randomUUID().toString());
	}

	private static @Nullable String iidOf(@Nullable UnmodifiableItemStack itemStack) {
		Assert.assertNotNull(itemStack);
		assert itemStack != null;
		ItemMeta itemMeta = Unsafe.assertNonNull(itemStack.getItemMeta());
		return itemMeta.getPersistentDataContainer().get(
				NamespacedKeyUtils.create("thirdplacetweaks", "iid"),
				PersistentDataType.STRING
		);
	}

	private static Predicate<@Nullable ItemStack> stockPredicate(ItemStack offerItem) {
		return NexoIntegration.stockPredicate(UnmodifiableItemStack.ofNonNull(offerItem));
	}

	@After
	public void restoreSetting() {
		Settings.ignoreItemIdentityTags = true;
	}

	@Test
	public void testItemsDifferingOnlyInIdentityTagsMatch() {
		ItemStack elytra1 = taggedElytra();
		ItemStack elytra2 = taggedElytra();
		ItemStack untagged = new ItemStack(Material.ELYTRA);
		Assert.assertFalse("Precondition", elytra1.isSimilar(elytra2));
		Assert.assertFalse("Precondition", elytra1.isSimilar(untagged));

		Assert.assertTrue(ItemIdentityTags.isSimilar(elytra1, elytra2));
		Assert.assertTrue(ItemIdentityTags.isSimilar(elytra1, untagged));
		Assert.assertTrue(ItemIdentityTags.isSimilar(untagged, elytra2));
		Assert.assertTrue(ItemIdentityTags.similarItems(elytra1).test(elytra2));
		Assert.assertTrue(stockPredicate(elytra1).test(elytra2));
		Assert.assertTrue(stockPredicate(untagged).test(elytra2));
		Assert.assertTrue(stockPredicate(elytra1).test(untagged));

		List<@Nullable ItemStack[]> stock = new ArrayList<>();
		stock.add(new @Nullable ItemStack[] { new ItemStack(Material.DIRT), elytra2 });
		Assert.assertTrue(InventoryUtils.containsAtLeast(stock, stockPredicate(elytra1), 1));
	}

	@Test
	public void testOtherDifferencesStillDoNotMatch() {
		ItemStack elytra = taggedElytra();

		ItemStack damaged = taggedElytra();
		Damageable damagedMeta = (Damageable) metaOf(damaged);
		damagedMeta.setDamage(100);
		damaged.setItemMeta(damagedMeta);
		Assert.assertFalse(ItemIdentityTags.isSimilar(elytra, damaged));
		Assert.assertFalse(stockPredicate(elytra).test(damaged));

		ItemStack enchanted = taggedElytra();
		ItemMeta enchantedMeta = metaOf(enchanted);
		enchantedMeta.addEnchant(Enchantment.UNBREAKING, 3, true);
		enchanted.setItemMeta(enchantedMeta);
		Assert.assertFalse(ItemIdentityTags.isSimilar(elytra, enchanted));
		Assert.assertFalse(stockPredicate(elytra).test(enchanted));

		ItemStack renamed = taggedElytra();
		ItemMeta renamedMeta = metaOf(renamed);
		renamedMeta.setItemName("Wings");
		renamed.setItemMeta(renamedMeta);
		Assert.assertFalse(ItemIdentityTags.isSimilar(elytra, renamed));
		Assert.assertFalse(stockPredicate(elytra).test(renamed));

		ItemStack foreignTag = taggedElytra();
		ItemMeta foreignTagMeta = metaOf(foreignTag);
		foreignTagMeta.getPersistentDataContainer().set(
				NamespacedKeyUtils.create("thirdplacetweaks", "souljar"),
				PersistentDataType.STRING,
				"zombie"
		);
		foreignTag.setItemMeta(foreignTagMeta);
		Assert.assertFalse(ItemIdentityTags.isSimilar(elytra, foreignTag));
		Assert.assertFalse(stockPredicate(elytra).test(foreignTag));

		ItemStack otherType = tagged(new ItemStack(Material.DIAMOND_SWORD), "x");
		Assert.assertFalse(ItemIdentityTags.isSimilar(elytra, otherType));
		Assert.assertFalse(stockPredicate(elytra).test(otherType));
	}

	@Test
	public void testStripKeepsOtherTags() {
		ItemStack item = taggedElytra();
		ItemMeta itemMeta = metaOf(item);
		itemMeta.getPersistentDataContainer().set(
				NamespacedKeyUtils.create("cmilib", "nbtcommands"),
				PersistentDataType.STRING,
				"say hi"
		);
		item.setItemMeta(itemMeta);
		ItemStack original = item.clone();

		ItemStack stripped = Unsafe.assertNonNull(ItemIdentityTags.strip(item));
		Assert.assertFalse(ItemIdentityTags.hasTags(stripped));
		Assert.assertTrue(metaOf(stripped).getPersistentDataContainer().has(
				NamespacedKeyUtils.create("cmilib", "nbtcommands")
		));
		// The given item is not modified:
		Assert.assertEquals(original, item);
		Assert.assertTrue(ItemIdentityTags.hasTags(item));

		// Items without identity tags are returned as is:
		ItemStack untagged = new ItemStack(Material.ELYTRA);
		Assert.assertSame(untagged, ItemIdentityTags.strip(untagged));
	}

	@Test
	public void testSettingOffRestoresStrictComparison() {
		ItemStack elytra1 = taggedElytra();
		ItemStack elytra2 = taggedElytra();
		Settings.ignoreItemIdentityTags = false;

		Assert.assertFalse(ItemIdentityTags.isSimilar(elytra1, elytra2));
		Assert.assertFalse(ItemIdentityTags.similarItems(elytra1).test(elytra2));
		Assert.assertFalse(stockPredicate(elytra1).test(elytra2));
		Assert.assertTrue(stockPredicate(elytra1).test(elytra1.clone()));

		List<@Nullable ItemStack[]> stock = new ArrayList<>();
		stock.add(new @Nullable ItemStack[] { elytra2 });
		Assert.assertNull(ItemIdentityTags.findTradedStockItem(
				stock,
				stockPredicate(elytra1),
				UnmodifiableItemStack.ofNonNull(elytra1)
		));
	}

	@Test
	public void testTradedStockItemKeepsItsTags() {
		ItemStack offerItem = tagged(new ItemStack(Material.ELYTRA), "offer");
		ItemStack stockItem = tagged(new ItemStack(Material.ELYTRA), "stock");
		ItemStack otherStockItem = tagged(new ItemStack(Material.ELYTRA), "other");
		@Nullable ItemStack[] contents = new @Nullable ItemStack[] {
				new ItemStack(Material.DIRT),
				stockItem,
				otherStockItem
		};
		List<@Nullable ItemStack[]> stock = new ArrayList<>();
		stock.add(contents);
		UnmodifiableItemStack resultItem = UnmodifiableItemStack.ofNonNull(offerItem);
		Predicate<@Nullable ItemStack> stockMatcher = stockPredicate(offerItem);

		UnmodifiableItemStack tradedItem = ItemIdentityTags.findTradedStockItem(
				stock,
				stockMatcher,
				resultItem
		);
		Assert.assertEquals("stock", iidOf(tradedItem));
		Assert.assertEquals("offer", iidOf(resultItem));

		// The trade removes the same physical stack:
		Assert.assertEquals(0, InventoryUtils.removeItems(stock, stockMatcher, 1));
		Assert.assertNull(contents[1]);
		Assert.assertSame(otherStockItem, contents[2]);
		Assert.assertEquals("stock", iidOf(UnmodifiableItemStack.ofNonNull(stockItem)));

		// An untagged stock item is handed out as is, without the offer's tags:
		@Nullable ItemStack[] untaggedContents = new @Nullable ItemStack[] {
				new ItemStack(Material.ELYTRA)
		};
		List<@Nullable ItemStack[]> untaggedStock = new ArrayList<>();
		untaggedStock.add(untaggedContents);
		UnmodifiableItemStack untaggedTradedItem = ItemIdentityTags.findTradedStockItem(
				untaggedStock,
				stockMatcher,
				resultItem
		);
		Assert.assertNotNull(untaggedTradedItem);
		Assert.assertFalse(ItemIdentityTags.hasTags(untaggedTradedItem));

		// Identical items need no replacement:
		List<@Nullable ItemStack[]> identicalStock = new ArrayList<>();
		identicalStock.add(new @Nullable ItemStack[] { offerItem.clone() });
		Assert.assertNull(ItemIdentityTags.findTradedStockItem(
				identicalStock,
				stockMatcher,
				resultItem
		));

		// Untagged items need no replacement:
		ItemStack untaggedOffer = new ItemStack(Material.ELYTRA);
		Assert.assertNull(ItemIdentityTags.findTradedStockItem(
				untaggedStock,
				stockPredicate(untaggedOffer),
				UnmodifiableItemStack.ofNonNull(untaggedOffer)
		));
	}

	// MERCHANT COST ITEMS

	private static ItemStack nexoSword(String id) {
		// Nexo stores its item id as a custom tag:
		ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
		ItemMeta itemMeta = metaOf(sword);
		itemMeta.getPersistentDataContainer().set(
				NexoItemIds.ITEM_ID_KEY,
				PersistentDataType.STRING,
				id
		);
		itemMeta.setItemName("Ruby Sword");
		itemMeta.setCustomModelData(10001);
		sword.setItemMeta(itemMeta);
		return sword;
	}

	private static boolean hasCustomData(ItemStack itemStack) {
		ItemStackComponentsData componentsData = ItemStackComponentsData.of(itemStack);
		return componentsData != null
				&& componentsData.contains(ItemIdentityTags.CUSTOM_DATA_COMPONENT);
	}

	@Test
	public void testMerchantCostItemDropsCustomDataOfUnstackableItems() {
		ItemStack sword = tagged(nexoSword("ruby_sword"), "a");
		ItemStack original = sword.clone();
		Assert.assertTrue("Precondition", hasCustomData(sword));

		ItemStack costItem = Unsafe.assertNonNull(ItemIdentityTags.toMerchantCostItem(sword));
		Assert.assertFalse(hasCustomData(costItem));
		Assert.assertEquals(original, sword);

		// The data that the client displays is kept:
		Assert.assertEquals(sword.getType(), costItem.getType());
		ItemMeta costMeta = metaOf(costItem);
		Assert.assertEquals("Ruby Sword", costMeta.getItemName());
		Assert.assertTrue(costMeta.hasCustomModelData());
		Assert.assertEquals(10001, costMeta.getCustomModelData());
		Assert.assertEquals(ItemIdentityTags.withoutCustomData(sword), costItem);

		// Stackable items keep their custom data:
		ItemStack coin = new ItemStack(Material.PAPER);
		ItemMeta coinMeta = metaOf(coin);
		coinMeta.getPersistentDataContainer().set(
				NamespacedKeyUtils.create("cmilib", "nbtcommands"),
				PersistentDataType.STRING,
				"say hi"
		);
		coin.setItemMeta(coinMeta);
		Assert.assertSame(coin, ItemIdentityTags.toMerchantCostItem(coin));

		// The setting off keeps the required items as they are:
		Settings.ignoreItemIdentityTags = false;
		Assert.assertSame(sword, ItemIdentityTags.toMerchantCostItem(sword));
	}

	@Test
	public void testRequiredItemMatchingRejectsLookAlikes() {
		UnmodifiableItemStack required = UnmodifiableItemStack.ofNonNull(
				tagged(nexoSword("ruby_sword"), "required")
		);
		ItemStack costItem = Unsafe.assertNonNull(
				ItemIdentityTags.toMerchantCostItem(ItemUtils.asItemStack(required))
		);

		// Same item, own identity tags:
		ItemStack genuine = tagged(nexoSword("ruby_sword"), "genuine");
		Assert.assertTrue(ItemIdentityTags.matchesRequiredItem(genuine, required));
		Assert.assertTrue(ItemIdentityTags.isSimilar(required, genuine));

		// A different Nexo item with the same base item, name and model:
		ItemStack otherId = tagged(nexoSword("emerald_sword"), "other");
		// Minecraft accepts it for the merchant cost item:
		Assert.assertTrue(Compat.getProvider().matches(otherId, costItem));
		Assert.assertFalse(ItemIdentityTags.matchesRequiredItem(otherId, required));
		Assert.assertFalse(ItemIdentityTags.isSimilar(required, otherId));

		// The same name and model, but without the Nexo data:
		ItemStack missingData = new ItemStack(Material.DIAMOND_SWORD);
		ItemMeta missingDataMeta = metaOf(missingData);
		missingDataMeta.setItemName("Ruby Sword");
		missingDataMeta.setCustomModelData(10001);
		missingData.setItemMeta(missingDataMeta);
		missingData = tagged(missingData, "missing");
		Assert.assertTrue(Compat.getProvider().matches(missingData, costItem));
		Assert.assertFalse(ItemIdentityTags.matchesRequiredItem(missingData, required));
		Assert.assertFalse(ItemIdentityTags.isSimilar(required, missingData));

		// Other custom tags that differ:
		ItemStack otherTags = tagged(nexoSword("ruby_sword"), "tags");
		ItemMeta otherTagsMeta = metaOf(otherTags);
		otherTagsMeta.getPersistentDataContainer().set(
				NamespacedKeyUtils.create("thirdplacetweaks", "souljar"),
				PersistentDataType.STRING,
				"zombie"
		);
		otherTags.setItemMeta(otherTagsMeta);
		Assert.assertFalse(ItemIdentityTags.matchesRequiredItem(otherTags, required));
		Assert.assertFalse(ItemIdentityTags.isSimilar(required, otherTags));

		// The setting off compares the identity tags as well:
		Settings.ignoreItemIdentityTags = false;
		Assert.assertFalse(ItemIdentityTags.matchesRequiredItem(genuine, required));
	}

	// HANDED OUT ITEMS

	@Test
	public void testHandedOutItemsIgnoreTheSetting() {
		Settings.ignoreItemIdentityTags = false;

		// Admin shops hand out copies without the offer item's identity tags:
		UnmodifiableItemStack adminResult = UnmodifiableItemStack.ofNonNull(
				tagged(new ItemStack(Material.ELYTRA), "template")
		);
		UnmodifiableItemStack handedOut = AdminShopTradingView.getHandedOutResultItem(adminResult);
		Assert.assertNotNull(handedOut);
		Assert.assertFalse(ItemIdentityTags.hasTags(handedOut));
		Assert.assertTrue(ItemIdentityTags.hasTags(adminResult));
		Assert.assertNull(AdminShopTradingView.getHandedOutResultItem(
				UnmodifiableItemStack.ofNonNull(new ItemStack(Material.ELYTRA))
		));

		// Player shops hand out the physical stock item that the trade removes:
		ItemStack offerItem = tagged(new ItemStack(Material.ELYTRA), "offer");
		ItemStack stockItem = tagged(new ItemStack(Material.ELYTRA), "stock");
		List<@Nullable ItemStack[]> stock = new ArrayList<>();
		stock.add(new @Nullable ItemStack[] { stockItem });
		UnmodifiableItemStack tradedItem = ItemIdentityTags.findTradedStockItem(
				stock,
				ItemUtils.itemsOfType(Material.ELYTRA),
				UnmodifiableItemStack.ofNonNull(offerItem)
		);
		Assert.assertEquals("stock", iidOf(tradedItem));
	}
}
