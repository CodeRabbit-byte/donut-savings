package com.coderabbit.donutsavings.client;

import java.util.Locale;

import com.coderabbit.donutsavings.core.Advisor;
import com.coderabbit.donutsavings.core.ChatParser;
import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Money;
import com.coderabbit.donutsavings.core.Tx;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Stops /pay before it leaves the client. Fabric's ALLOW_COMMAND event fires for typed commands
 * and for commands run by clicking chat text, and returning false means the server never sees it.
 */
public final class PayGuard {
	private static final long CONFIRM_WINDOW = 15_000;
	private static String pendingCommand;
	private static long pendingAt;

	private PayGuard() {
	}

	/** @param command without the leading slash */
	public static boolean allow(String command) {
		Config c = Savings.config();
		String name = ChatParser.commandName(command);

		if (!isPayCommand(c, name)) {
			return true;
		}

		if (!Session.active()) {
			return true;
		}

		String[] args = command.trim().split("\\s+");

		// Any form is blocked, even "/pay" alone, in case the server opens a payment menu for it.
		if (c.blocksPay()) {
			Savings.data().paymentsBlocked++;
			Savings.markDirty();
			Notify.bad("PAYMENT BLOCKED: You're supposed to be saving your money!");
			Notify.info(c.strictMode
					? "Strict Savings Mode blocks every /pay. Turn it off in /savings settings if you really must."
					: "/pay is blocked in /savings settings (Block /pay).");
			Notify.denied();
			DebugLog.write("pay", "blocked: /" + command);
			return false;
		}

		if (args.length < 3) {
			return true; // "/pay" or "/pay NAME" only gets the server's usage text
		}

		double amount = Money.parse(args[2]);
		double total = amount > 0 ? amount * (1 + c.payTaxPercent / 100.0) : Double.NaN;
		long now = System.currentTimeMillis();
		Advisor.Verdict v = Advisor.evaluate(Savings.ledger(), Advisor.Kind.TRANSFER, args[1], total, now);

		if (v.blocked()) {
			Notify.bad("PAYMENT BLOCKED: " + v.headline);

			for (String b : v.blocks) {
				Notify.send(Component.literal(" - " + b).withStyle(ChatFormatting.RED));
			}

			Notify.denied();
			pendingCommand = null;
			return false;
		}

		boolean ask = c.confirms() || (c.warns() && !v.warnings.isEmpty());

		if (ask && !(command.trim().equals(pendingCommand) && now - pendingAt <= CONFIRM_WINDOW)) {
			pendingCommand = command.trim();
			pendingAt = now;
			Notify.send(Component.literal(v.headline).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			Notify.info("Paying " + args[1] + " " + (amount > 0 ? Money.format(amount) : args[2])
					+ (amount > 0 && c.payTaxPercent > 0 ? String.format(" (+%s tax = %s)", Money.format(total - amount), Money.format(total)) : "")
					+ (Double.isNaN(v.after) ? "" : ", leaving " + Money.format(v.after)));

			if (c.warns()) {
				for (String w : v.warnings) {
					Notify.send(Component.literal(" - " + w).withStyle(ChatFormatting.YELLOW));
				}
			}

			Notify.send(Component.literal("Not sent. Type the exact same command again within 15s if you really mean it.")
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
			Notify.denied();
			return false;
		}

		pendingCommand = null;
		Savings.reconciler().expect(args[1], total, Tx.Kind.TRANSFER_OUT, now);
		return true;
	}

	static boolean isPayCommand(Config c, String name) {
		for (String p : c.payCommands) {
			if (p != null && name.equals(p.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", ""))) {
				return true;
			}
		}

		return false;
	}
}
