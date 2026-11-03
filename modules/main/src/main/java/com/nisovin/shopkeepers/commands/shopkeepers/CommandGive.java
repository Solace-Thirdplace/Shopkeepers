package com.nisovin.shopkeepers.commands.shopkeepers;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.ShopkeepersPlugin;
import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.api.shopkeeper.ShopType;
import com.nisovin.shopkeepers.api.shopobjects.ShopObjectType;
import com.nisovin.shopkeepers.commands.arguments.ShopObjectTypeArgument;
import com.nisovin.shopkeepers.commands.arguments.ShopTypeArgument;
import com.nisovin.shopkeepers.commands.arguments.ShopTypeFilter;
import com.nisovin.shopkeepers.commands.lib.Command;
import com.nisovin.shopkeepers.commands.lib.CommandException;
import com.nisovin.shopkeepers.commands.lib.CommandInput;
import com.nisovin.shopkeepers.commands.lib.arguments.BoundedIntegerArgument;
import com.nisovin.shopkeepers.commands.lib.arguments.PlayerArgument;
import com.nisovin.shopkeepers.commands.lib.arguments.SenderPlayerFallback;
import com.nisovin.shopkeepers.commands.lib.context.CommandContextView;
import com.nisovin.shopkeepers.lang.Messages;
import com.nisovin.shopkeepers.shopcreation.ShopCreationItem;
import com.nisovin.shopkeepers.util.bukkit.SchedulerUtils;
import com.nisovin.shopkeepers.util.bukkit.TextUtils;
import com.nisovin.shopkeepers.util.inventory.InventoryUtils;

class CommandGive extends Command {

	private static final String ARGUMENT_PLAYER = "player";
	private static final String ARGUMENT_SHOP_TYPE = "shop-type";
	private static final String ARGUMENT_OBJECT_TYPE = "object-type";
	private static final String ARGUMENT_AMOUNT = "amount";

	CommandGive() {
		super("give");

		// Set permission:
		this.setPermission(ShopkeepersPlugin.GIVE_PERMISSION);

		// Set description:
		this.setDescription(Messages.commandDescriptionGive);

		// Arguments:
		this.addArgument(new SenderPlayerFallback(new PlayerArgument(ARGUMENT_PLAYER)));
		// Only player shop types are supported by the shop creation item currently:
		this.addArgument(new ShopTypeArgument(ARGUMENT_SHOP_TYPE, ShopTypeFilter.PLAYER).optional());
		this.addArgument(new ShopObjectTypeArgument(ARGUMENT_OBJECT_TYPE).optional());
		// Upper limit to avoid accidental misuse:
		this.addArgument(new BoundedIntegerArgument(ARGUMENT_AMOUNT, 1, 1024).orDefaultValue(1));
	}

	@Override
	protected void execute(CommandInput input, CommandContextView context) throws CommandException {
		CommandSender sender = input.getSender();

		Player targetPlayer = context.get(ARGUMENT_PLAYER);
		boolean targetSelf = (sender.equals(targetPlayer));

		ShopType<?> shopType = context.getOrNull(ARGUMENT_SHOP_TYPE);
		ShopObjectType<?> shopObjectType = context.getOrNull(ARGUMENT_OBJECT_TYPE);

		int amount = context.get(ARGUMENT_AMOUNT);
		assert amount >= 1 && amount <= 1024;

		ItemStack item = ShopCreationItem.create(amount);

		var shopCreationItem = new ShopCreationItem(item);
		if (shopType != null) {
			shopCreationItem.setShopType(shopType);
		}
		if (shopObjectType != null) {
			shopCreationItem.setObjectType(shopObjectType);
		}

		shopCreationItem.applyItemMeta();

		// The target player's inventory can only be modified, and items dropped at their location, on
		// the thread that owns the player (Folia: the command may run on another region's thread or
		// on the global region thread).
		SchedulerUtils.runOnEntityOrOmit(SKShopkeepersPlugin.getInstance(), targetPlayer, () -> {
			PlayerInventory inventory = targetPlayer.getInventory();
			@Nullable ItemStack[] contents = Unsafe.castNonNull(inventory.getStorageContents());
			int remaining = InventoryUtils.addItems(contents, item);
			InventoryUtils.setStorageContents(inventory, contents);
			if (remaining > 0) {
				item.setAmount(remaining);
				targetPlayer.getWorld().dropItem(targetPlayer.getEyeLocation(), item);
			}

			// Success:
			// TODO Show shop creation item via hover text?
			// Inform target player:
			TextUtils.sendMessage(targetPlayer, Messages.shopCreationItemsReceived,
					"amount", amount
			);
			if (!targetSelf) {
				// Inform command executor:
				TextUtils.sendMessage(sender, Messages.shopCreationItemsGiven,
						"player", TextUtils.getPlayerText(targetPlayer),
						"amount", amount
				);
			}
		});
	}
}
