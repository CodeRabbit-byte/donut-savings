package com.coderabbit.donutsavings.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.coderabbit.donutsavings.client.ui.DashboardScreen;
import com.coderabbit.donutsavings.client.ui.SettingsScreen;
import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Ledger;
import com.coderabbit.donutsavings.core.Money;
import com.coderabbit.donutsavings.core.Tx;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public class DonutSavingsClient implements ClientModInitializer {
	public static final String MOD_ID = "donutsavings";
	public static final Logger LOGGER = LoggerFactory.getLogger("DonutSavings");

	private static KeyMapping dashboardKey;
	/** A screen to open on the next tick (from a command or a click handler). */
	private static Screen pending;

	@Override
	public void onInitializeClient() {
		Savings.load();

		dashboardKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.donutsavings.dashboard", InputConstants.Type.KEYSYM, InputConstants.KEY_U, KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(DonutSavingsClient::tick);

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				Session.onServerMessage(message);
			}
		});

		ClientSendMessageEvents.ALLOW_COMMAND.register(PayGuard::allow);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Session.joined());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Session.left());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Savings.saveData(true));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(command()));

		LOGGER.info("Donut Savings loaded. Strict mode: {}", Savings.config().strictMode);
	}

	public static void openLater(Screen screen) {
		pending = screen;
	}

	private static void tick(Minecraft mc) {
		if (pending != null) {
			Screen s = pending;
			pending = null;
			mc.setScreen(s);
		}

		while (dashboardKey.consumeClick()) {
			if (mc.screen == null) {
				mc.setScreen(new DashboardScreen(null));
			}
		}

		Session.tick(mc);
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
		return ClientCommandManager.literal("savings")
				.executes(ctx -> open(new DashboardScreen(null)))
				.then(ClientCommandManager.literal("settings").executes(ctx -> open(new SettingsScreen(null))))
				.then(ClientCommandManager.literal("status").executes(ctx -> status()))
				.then(ClientCommandManager.literal("reload").executes(ctx -> {
					Savings.reloadConfig();
					Notify.good("Reloaded config/donutsavings/config.json.");
					return 1;
				}))
				.then(ClientCommandManager.literal("debug").executes(ctx -> {
					Config c = Savings.config().copy();
					c.debugLog = !c.debugLog;
					Savings.setConfig(c);
					Notify.info("Debug log " + (c.debugLog ? "ON: raw chat, scoreboard and menu clicks go to " + DebugLog.file()
							: "OFF."));
					return 1;
				}))
				.then(ClientCommandManager.literal("lock").executes(ctx -> {
					Config c = Savings.config().copy();
					c.spendingLock = true;
					Savings.setConfig(c);
					Notify.bad("SPENDING LOCK ON. Every purchase the mod can see is blocked. Unlock in /savings settings.");
					return 1;
				}))
				.then(ClientCommandManager.literal("balance")
						.then(ClientCommandManager.argument("amount", StringArgumentType.greedyString())
								.executes(ctx -> manualBalance(StringArgumentType.getString(ctx, "amount")))))
				.then(ClientCommandManager.literal("spent")
						.then(ClientCommandManager.argument("amountAndNote", StringArgumentType.greedyString())
								.executes(ctx -> manual(ctx, Tx.Kind.PURCHASE))))
				.then(ClientCommandManager.literal("earned")
						.then(ClientCommandManager.argument("amountAndNote", StringArgumentType.greedyString())
								.executes(ctx -> manual(ctx, Tx.Kind.OTHER_IN))));
	}

	private static int open(Screen s) {
		pending = s; // the chat screen is still closing; open next tick
		return 1;
	}

	private static int manualBalance(String text) {
		double v = Money.parse(text);

		if (v < 0) {
			Notify.warn("Not an amount: " + text + " (try 1.5b, 250m)");
			return 0;
		}

		// Set directly, not as a reading: a typed number should not create a balance-change entry.
		Savings.reconciler().reset();
		Savings.ledger().balance(v, 0, "typed", System.currentTimeMillis());
		Savings.markDirty();
		Notify.good("Balance set to " + Money.format(v) + ".");
		return 1;
	}

	private static int manual(CommandContext<FabricClientCommandSource> ctx, Tx.Kind kind) {
		String raw = StringArgumentType.getString(ctx, "amountAndNote").trim();
		int space = raw.indexOf(' ');
		String amountText = space < 0 ? raw : raw.substring(0, space);
		String note = space < 0 ? "" : raw.substring(space + 1).trim();
		double v = Money.parse(amountText);

		if (v <= 0) {
			Notify.warn("Not an amount: " + amountText + " (try 5m, 250k)");
			return 0;
		}

		Savings.ledger().record(new Tx(System.currentTimeMillis(), kind, v, note.isEmpty() ? "added by hand" : note, "manual", false));
		return 1;
	}

	private static int status() {
		Ledger l = Savings.ledger();
		Config c = Savings.config();
		long now = System.currentTimeMillis();
		Notify.info("Tracking: " + (Session.active() ? "ON (" + Session.serverAddress() + ")" : "paused (not on " + c.serverFilter + ")")
				+ ", strict mode " + (c.strictMode ? "ON" : "off") + ", /pay " + (c.blocksPay() ? "blocked" : "allowed with confirm"));
		Notify.info("Balance " + (l.hasBalance() ? Money.format(l.balance()) + " (" + Savings.data().balanceSource + ")" : "unknown")
				+ ", spent today " + Money.format(l.spentToday(now)) + ", allowance left " + Money.format(l.remainingAllowance(now)));
		return 1;
	}
}
