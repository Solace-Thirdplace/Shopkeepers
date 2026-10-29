package com.nisovin.shopkeepers.shopkeeper.admin;

import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.api.shopkeeper.ShopCreationData;
import com.nisovin.shopkeepers.api.shopkeeper.ShopkeeperCreateException;
import com.nisovin.shopkeepers.api.shopkeeper.admin.AdminShopCreationData;
import com.nisovin.shopkeepers.api.shopkeeper.admin.AdminShopkeeper;
import com.nisovin.shopkeepers.api.ui.DefaultUITypes;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import com.nisovin.shopkeepers.lang.Messages;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopkeeper.ShopkeeperData;
import com.nisovin.shopkeepers.ui.lib.UIState;
import com.nisovin.shopkeepers.ui.lib.View;
import com.nisovin.shopkeepers.ui.trading.Trade;
import com.nisovin.shopkeepers.ui.trading.TradingView;
import com.nisovin.shopkeepers.ui.trading.TradingViewProvider;
import com.nisovin.shopkeepers.util.bukkit.PermissionUtils;
import com.nisovin.shopkeepers.util.bukkit.TextUtils;
import com.nisovin.shopkeepers.util.data.property.BasicProperty;
import com.nisovin.shopkeepers.util.data.property.DataKeyAccessor;
import com.nisovin.shopkeepers.util.data.property.EmptyDataPredicates;
import com.nisovin.shopkeepers.util.data.property.Property;
import com.nisovin.shopkeepers.util.data.serialization.InvalidDataException;
import com.nisovin.shopkeepers.util.data.serialization.java.StringSerializers;
import com.nisovin.shopkeepers.util.inventory.ItemIdentityTags;
import com.nisovin.shopkeepers.util.java.StringUtils;

public abstract class AbstractAdminShopkeeper
		extends AbstractShopkeeper implements AdminShopkeeper {

	public static class AdminShopTradingViewProvider extends TradingViewProvider {

		protected AdminShopTradingViewProvider(AbstractAdminShopkeeper shopkeeper) {
			super(shopkeeper);
		}

		@Override
		public AbstractAdminShopkeeper getShopkeeper() {
			return (AbstractAdminShopkeeper) super.getShopkeeper();
		}

		@Override
		public boolean canAccess(Player player, boolean silent) {
			if (!super.canAccess(player, silent)) return false;

			// Check trading permission:
			String tradePermission = this.getShopkeeper().getTradePermission();
			if (tradePermission != null && !PermissionUtils.hasPermission(player, tradePermission)) {
				if (!silent) {
					this.debugNotOpeningUI(player, "Player is missing the custom trade permission '"
							+ tradePermission + "'.");
					TextUtils.sendMessage(player, Messages.missingCustomTradePerm);
				}
				return false;
			}

			return true;
		}

		@Override
		protected @Nullable View createView(Player player, UIState uiState) {
			return new AdminShopTradingView(this, player, uiState);
		}
	}

	public static class AdminShopTradingView extends TradingView {

		protected AdminShopTradingView(
				AdminShopTradingViewProvider provider,
				Player player,
				UIState uiState
		) {
			super(provider, player, uiState);
		}

		/**
		 * Gets the item that the trading player receives in place of the given result item.
		 * <p>
		 * Every trade hands out a new copy of the result item. Without its
		 * {@link ItemIdentityTags item identity tags}, the copies do not share the identity of the
		 * offer item. This applies regardless of whether the identity tags are
		 * {@link ItemIdentityTags#isIgnored() ignored} by item comparisons.
		 * 
		 * @param resultItem
		 *            the result item of the trading recipe, not <code>null</code>
		 * @return the item to hand out, or <code>null</code> to hand out the result item as is
		 */
		public static @Nullable UnmodifiableItemStack getHandedOutResultItem(
				UnmodifiableItemStack resultItem
		) {
			UnmodifiableItemStack strippedResultItem = ItemIdentityTags.strip(resultItem);
			if (strippedResultItem == resultItem) return null;
			return strippedResultItem;
		}

		@Override
		protected void setupTrade(Trade trade) {
			super.setupTrade(trade);

			UnmodifiableItemStack handedOutItem = getHandedOutResultItem(
					trade.getTradingRecipe().getResultItem()
			);
			if (handedOutItem == null) return;

			assert !trade.isTradeEventCalled();
			trade.getTradeEvent().setResultItem(handedOutItem);
		}
	}

	// Null if no additional trading permission is required. Not empty.
	private @Nullable String tradePermission = null;

	/**
	 * Creates a new and not yet initialized {@link AbstractAdminShopkeeper}.
	 * <p>
	 * See {@link AbstractShopkeeper} for details on initialization.
	 */
	protected AbstractAdminShopkeeper() {
	}

	/**
	 * Expects an {@link AdminShopCreationData}.
	 */
	@Override
	protected void loadFromCreationData(int id, ShopCreationData shopCreationData)
			throws ShopkeeperCreateException {
		super.loadFromCreationData(id, shopCreationData);
	}

	@Override
	protected void setup() {
		this.registerViewProviderIfMissing(DefaultUITypes.TRADING(), () -> {
			return new AdminShopTradingViewProvider(this);
		});
		super.setup();
	}

	@Override
	public void loadDynamicState(ShopkeeperData shopkeeperData) throws InvalidDataException {
		super.loadDynamicState(shopkeeperData);
		this.loadTradePermission(shopkeeperData);
	}

	@Override
	public void saveDynamicState(ShopkeeperData shopkeeperData, boolean saveAll) {
		super.saveDynamicState(shopkeeperData, saveAll);
		this.saveTradePermission(shopkeeperData);
	}

	// TRADE PERMISSION

	public static final Property<@Nullable String> TRADE_PERMISSION = new BasicProperty<@Nullable String>()
			.dataAccessor(new DataKeyAccessor<>("tradePerm", StringSerializers.SCALAR)
					.emptyDataPredicate(EmptyDataPredicates.EMPTY_STRING)
			)
			.nullable() // Null if no additional trading permission is required
			.defaultValue(null)
			.build();

	private void loadTradePermission(ShopkeeperData shopkeeperData) throws InvalidDataException {
		assert shopkeeperData != null;
		this._setTradePermission(shopkeeperData.get(TRADE_PERMISSION));
	}

	private void saveTradePermission(ShopkeeperData shopkeeperData) {
		assert shopkeeperData != null;
		shopkeeperData.set(TRADE_PERMISSION, tradePermission);
	}

	@Override
	public @Nullable String getTradePermission() {
		return tradePermission;
	}

	@Override
	public void setTradePermission(@Nullable String tradePermission) {
		this._setTradePermission(tradePermission);
		this.markDirty();
	}

	private void _setTradePermission(@Nullable String tradePermission) {
		this.tradePermission = StringUtils.getNotEmpty(tradePermission);
	}
}
