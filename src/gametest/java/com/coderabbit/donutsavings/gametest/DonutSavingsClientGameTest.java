package com.coderabbit.donutsavings.gametest;

import com.coderabbit.donutsavings.client.Savings;
import com.coderabbit.donutsavings.client.ui.DashboardScreen;
import com.coderabbit.donutsavings.client.ui.PurchaseConfirmScreen;
import com.coderabbit.donutsavings.client.ui.SettingsScreen;
import com.coderabbit.donutsavings.client.ui.StrictOffScreen;
import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Data;
import com.coderabbit.donutsavings.core.Tx;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the mod in a real client. Singleplayer stands in for DonutSMP (the server filter is off),
 * /tellraw stands in for Donut's chat lines, a scoreboard line stands in for its sidebar, and a
 * chest named "Auction House" holding a "Confirm" pane stands in for a purchase menu.
 * Run with {@code ./gradlew runClientGameTest}.
 */
public class DonutSavingsClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		TestInput input = context.getInput();
		context.runOnClient(client -> {
			Config c = new Config();
			c.onlyOnDonut = false;
			c.confirmDelaySeconds = 0;
			c.unnecessaryCooldownSeconds = 0;
			Savings.setConfig(c);
			Data d = Savings.data();
			d.history.clear();
		});

		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			TestServerContext server = sp.getServer();

			// ---- balance from the scoreboard sidebar
			server.runCommand("scoreboard objectives add donut dummy \"DonutSMP\"");
			server.runCommand("scoreboard objectives setdisplay sidebar donut");
			server.runCommand("scoreboard players set line1 donut 3");
			server.runCommand("scoreboard players display name line1 donut \"Money $1.5B\"");
			server.runCommand("scoreboard players set line2 donut 2");
			server.runCommand("scoreboard players display name line2 donut \"Kills 12\"");
			context.waitTicks(20 * 9); // 5s join grace, then two equal readings
			context.runOnClient(client -> {
				check(Savings.data().balance == 1.5e9, "scoreboard balance should be 1.5B, got " + Savings.data().balance);
				check("scoreboard".equals(Savings.data().balanceSource), "source " + Savings.data().balanceSource);
			});

			// ---- chat lines in Donut's /pay format
			say(context, server, "You paid Steve $90 (+$2 tax)");
			say(context, server, "Alex paid you $1.1M.");
			context.runOnClient(client -> {
				Data d = Savings.data();
				check(d.history.size() == 2, "two transactions expected, have " + d.history.size());
				check(d.history.get(0).kind == Tx.Kind.TRANSFER_OUT && d.history.get(0).amount == 92, "pay sent incl. tax");
				check(d.history.get(1).kind == Tx.Kind.TRANSFER_IN && d.history.get(1).amount == 1.1e6, "pay received");
			});

			// ---- /pay is stopped before it is sent
			context.runOnClient(client -> client.player.connection.sendCommand("pay Steve 5m"));
			context.waitTicks(2);
			context.runOnClient(client -> client.player.connection.sendCommand("essentials:pay Bob 1"));
			context.waitTicks(2);
			context.runOnClient(client -> check(Savings.data().paymentsBlocked == 2,
					"both /pay forms should be blocked, counted " + Savings.data().paymentsBlocked));
			context.takeScreenshot("donutsavings-pay-blocked");

			// ---- a purchase menu
			BlockPos chest = context.computeOnClient(client -> client.player.blockPosition().offset(2, 0, 0));
			server.runCommand("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
					+ " chest{CustomName:\"Auction House\",Items:[{Slot:13b,id:\"minecraft:lime_stained_glass_pane\",count:1,"
					+ "components:{\"minecraft:custom_name\":\"Confirm\",\"minecraft:lore\":[\"Price: $5M\"]}},"
					+ "{Slot:11b,id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:custom_name\":\"Cool Sword\"}}]}");
			context.waitTicks(5);
			openChest(context, chest);

			// Click Confirm: held back, the check screen opens, the pane stays in the chest.
			clickSlot(context, 13);
			context.waitForScreen(PurchaseConfirmScreen.class);
			context.waitTicks(3);
			context.takeScreenshot("donutsavings-confirm");
			check(chestSlot(context, server, chest, 13), "the click must not reach the server yet");
			context.clickScreenButton("Walk away");
			context.waitForScreen(ContainerScreen.class);
			context.waitTicks(3);
			context.runOnClient(client -> check(Savings.data().walkedAway == 1 && Savings.data().walkedAwayAmount == 5e6,
					"walk away should count 5M kept"));
			check(chestSlot(context, server, chest, 13), "walking away sends nothing");

			// Spending lock: no buy button at all.
			context.runOnClient(client -> {
				Config c = Savings.config().copy();
				c.spendingLock = true;
				Savings.setConfig(c);
			});
			clickSlot(context, 13);
			context.waitForScreen(PurchaseConfirmScreen.class);
			context.waitTicks(3);
			context.runOnClient(client -> check(client.screen.children().stream()
					.noneMatch(w -> w instanceof Button b && b.getMessage().getString().startsWith("Buy")),
					"a blocked purchase must have no buy button"));
			context.takeScreenshot("donutsavings-blocked");
			input.pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(ContainerScreen.class);
			context.runOnClient(client -> {
				Config c = Savings.config().copy();
				c.spendingLock = false;
				Savings.setConfig(c);
			});

			// Buy anyway: the same click is replayed and the server gets it.
			clickSlot(context, 13);
			context.waitForScreen(PurchaseConfirmScreen.class);
			context.waitTicks(3);
			context.clickScreenButton("Buy anyway");
			context.waitForScreen(ContainerScreen.class);
			context.waitTicks(5);
			check(!chestSlot(context, server, chest, 13), "after Buy anyway the click should have reached the server");
			context.runOnClient(client -> client.player.closeContainer());
			context.waitTicks(3);

			// ---- screens render without crashing
			input.pressKey(InputConstants.KEY_U);
			context.waitForScreen(DashboardScreen.class);
			context.waitTicks(2);
			context.takeScreenshot("donutsavings-overview");

			for (String tab : new String[] {"Goals", "History", "Achievements", "Protection"}) {
				context.clickScreenButton(tab);
				context.waitTicks(2);
				context.takeScreenshot("donutsavings-" + tab.toLowerCase());
			}

			context.clickScreenButton("Settings");
			context.waitForScreen(SettingsScreen.class);
			context.waitTicks(2);
			context.takeScreenshot("donutsavings-settings");
			context.clickScreenButton("STRICT SAVINGS MODE: OFF");
			context.waitTicks(2);
			context.runOnClient(client -> check(Savings.config().strictMode, "strict mode should switch on at once"));
			context.clickScreenButton("STRICT SAVINGS MODE: ON");
			context.waitForScreen(StrictOffScreen.class);
			context.waitTicks(2);
			context.takeScreenshot("donutsavings-strict-off");
			input.pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(SettingsScreen.class);
			context.runOnClient(client -> check(Savings.config().strictMode, "escape must keep strict mode on"));
			input.pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(DashboardScreen.class);
			input.pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(null);

			// Strict mode blocks /pay too.
			context.runOnClient(client -> client.player.connection.sendCommand("pay Steve 1"));
			context.waitTicks(2);
			context.runOnClient(client -> check(Savings.data().paymentsBlocked == 3, "strict mode blocks /pay"));
			context.waitTicks(20);
		}
	}

	private static void openChest(ClientGameTestContext context, BlockPos chest) {
		context.runOnClient(client -> client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false)));
		context.waitForScreen(ContainerScreen.class);
		context.waitTicks(3);
	}

	private static void clickSlot(ClientGameTestContext context, int slot) {
		context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId, slot, 0,
				ClickType.PICKUP, client.player));
		context.waitTicks(1);
	}

	/** True when the chest on the server still holds something in that slot. */
	private static boolean chestSlot(ClientGameTestContext context, TestServerContext server, BlockPos pos, int slot) {
		return server.computeOnServer(s -> {
			var be = s.overworld().getBlockEntity(pos);
			return be instanceof Container c && !c.getItem(slot).isEmpty();
		});
	}

	private static void say(ClientGameTestContext context, TestServerContext server, String line) {
		server.runCommand("tellraw @a \"" + line + "\"");
		context.waitTicks(3);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
