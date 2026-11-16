package com.coderabbit.donutsavings.client;

import java.util.ArrayList;
import java.util.List;

import com.coderabbit.donutsavings.client.ui.PurchaseConfirmScreen;
import com.coderabbit.donutsavings.core.Advisor;
import com.coderabbit.donutsavings.core.ChatParser;
import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Money;
import com.coderabbit.donutsavings.core.Text;
import com.coderabbit.donutsavings.core.Tx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Catches clicks on "buy" buttons in server menus (Auction House, shop) before the click packet
 * is sent, and puts the confirmation screen in front of them.
 *
 * <p>The click is held, not sent. If the player confirms, the exact same click is replayed into
 * the still-open menu; if not, nothing ever reaches the server. The mod never clicks anything the
 * player did not click.
 */
public final class PurchaseGuard {
	/** The held click. */
	public record Attempt(int containerId, int slotId, int button, ClickType type, ItemStack stack, String item,
			double price, Screen menuScreen, long time) {
	}

	/** The last priced item looked at in a market menu, for confirm pages that don't repeat it. */
	private static String contextItem;
	private static double contextPrice = Double.NaN;
	private static long contextAt;
	private static boolean replaying;

	private PurchaseGuard() {
	}

	/** Called from the mixin. Returns true to cancel the click. */
	public static boolean onClick(int containerId, int slotId, int button, ClickType type) {
		if (replaying || type == ClickType.CLONE) {
			return false;
		}

		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Screen screen = mc.screen;

		if (player == null || !(screen instanceof AbstractContainerScreen<?>) || screen instanceof InventoryScreen
				|| screen instanceof CreativeModeInventoryScreen || !Session.active()) {
			return false;
		}

		AbstractContainerMenu menu = player.containerMenu;

		if (menu == null || menu == player.inventoryMenu || menu.containerId != containerId || slotId < 0
				|| slotId >= menu.slots.size()) {
			return false;
		}

		Slot slot = menu.slots.get(slotId);

		if (slot.container instanceof Inventory) {
			return false; // the player's own inventory below the menu
		}

		ItemStack stack = slot.getItem();

		if (stack.isEmpty()) {
			return false;
		}

		Config c = Savings.config();
		String title = Text.normalize(screen.getTitle().getString());
		String name = Text.normalize(stack.getHoverName().getString());
		List<String> lore = lore(stack);
		String loreText = String.join(" \n ", lore);

		if (c.debugLog) {
			DebugLog.write("click", "menu='" + title + "' slot=" + slotId + " item='" + name + "' lore=" + lore);
		}

		boolean buyButton = ChatParser.match(c.buyButton, name) != null && ChatParser.match(c.notBuyButton, name) == null;
		boolean clickToBuy = ChatParser.match(c.clickToBuyLore, loreText) != null;
		Money.Found here = Money.findPrice(name + " \n " + loreText);
		long now = System.currentTimeMillis();

		if (!buyButton && !clickToBuy) {
			// Probably opening a listing's confirm page: remember what and how much.
			if (here != null && ChatParser.match(c.marketTitle, title) != null) {
				contextItem = name;
				contextPrice = here.value();
				contextAt = now;
			}

			return false;
		}

		String item;
		double price;

		if (clickToBuy) {
			item = name;
			price = here != null ? here.value() : Double.NaN;
		} else {
			// A confirm button. The price may be on the button, on the item shown in the menu,
			// or only on the listing clicked before.
			Priced shown = shownItem(menu, slotId, c);
			boolean contextFresh = contextItem != null && now - contextAt < 120_000;
			item = shown != null ? shown.name : contextFresh ? contextItem : title;

			if (here != null) {
				price = here.value();
			} else if (shown != null) {
				price = shown.price;
			} else {
				price = contextFresh ? contextPrice : Double.NaN;
			}
		}

		Advisor.Verdict v = Advisor.evaluate(Savings.ledger(), Advisor.Kind.PURCHASE, item, price, now);
		boolean ask = c.confirms() || v.blocked() || (c.warns() && !v.warnings.isEmpty());

		if (!ask) {
			Savings.reconciler().expect(v.item, price, Tx.Kind.PURCHASE, now);
			return false;
		}

		Attempt a = new Attempt(containerId, slotId, button, type, stack.copy(), v.item, price, screen, now);
		// Open next tick: we are inside the click handler of the menu screen.
		DonutSavingsClient.openLater(new PurchaseConfirmScreen(a, v));
		return true;
	}

	/** Sends the held click after the player confirmed. Returns false if the menu changed. */
	public static boolean replay(Attempt a) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;

		if (player == null || mc.gameMode == null) {
			return false;
		}

		AbstractContainerMenu menu = player.containerMenu;

		if (menu == null || menu.containerId != a.containerId() || a.slotId() >= menu.slots.size()
				|| !ItemStack.isSameItemSameComponents(menu.slots.get(a.slotId()).getItem(), a.stack())) {
			return false;
		}

		replaying = true;

		try {
			mc.gameMode.handleInventoryMouseClick(a.containerId(), a.slotId(), a.button(), a.type(), player);
		} finally {
			replaying = false;
		}

		Savings.reconciler().expect(a.item(), a.price(), Tx.Kind.PURCHASE, System.currentTimeMillis());
		return true;
	}

	private record Priced(String name, double price) {
	}

	/** The one other item in the menu that carries a price (the thing being bought). */
	private static Priced shownItem(AbstractContainerMenu menu, int buttonSlot, Config c) {
		Priced found = null;

		for (int i = 0; i < menu.slots.size(); i++) {
			Slot s = menu.slots.get(i);

			if (i == buttonSlot || s.container instanceof Inventory || s.getItem().isEmpty()) {
				continue;
			}

			String name = Text.normalize(s.getItem().getHoverName().getString());

			if (ChatParser.match(c.buyButton, name) != null || ChatParser.match(c.notBuyButton, name) != null) {
				continue;
			}

			Money.Found f = Money.findPrice(name + " \n " + String.join(" \n ", lore(s.getItem())));

			if (f != null) {
				if (found != null) {
					return null; // more than one priced item: can't tell which is being bought
				}

				found = new Priced(name, f.value());
			}
		}

		return found;
	}

	private static List<String> lore(ItemStack stack) {
		List<String> out = new ArrayList<>();
		ItemLore lore = stack.get(DataComponents.LORE);

		if (lore != null) {
			for (Component line : lore.lines()) {
				String s = Text.normalize(line.getString());

				if (!s.isEmpty()) {
					out.add(s);
				}
			}
		}

		return out;
	}
}
