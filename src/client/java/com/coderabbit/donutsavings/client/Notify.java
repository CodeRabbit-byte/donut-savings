package com.coderabbit.donutsavings.client;

import com.coderabbit.donutsavings.core.Goal;
import com.coderabbit.donutsavings.core.Ledger;
import com.coderabbit.donutsavings.core.Money;
import com.coderabbit.donutsavings.core.Tx;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** Every chat line and sound the mod makes. Nothing here is sent to the server. */
public final class Notify {
	private static final Component PREFIX = Component.literal("[Savings] ").withStyle(ChatFormatting.GOLD);

	private Notify() {
	}

	public static void send(Component message) {
		Minecraft mc = Minecraft.getInstance();

		if (mc.gui != null) {
			mc.gui.getChat().addMessage(Component.empty().append(PREFIX).append(message));
		}
	}

	public static void info(String message) {
		send(Component.literal(message).withStyle(ChatFormatting.GRAY));
	}

	public static void good(String message) {
		send(Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	public static void warn(String message) {
		send(Component.literal(message).withStyle(ChatFormatting.YELLOW));
	}

	public static void bad(String message) {
		send(Component.literal(message).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
	}

	public static void sound(SoundEvent sound, float pitch) {
		Minecraft mc = Minecraft.getInstance();

		if (Savings.config().sounds && mc.getSoundManager() != null) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
		}
	}

	public static void denied() {
		sound(SoundEvents.VILLAGER_NO, 1.0F);
	}

	public static void celebrate() {
		sound(SoundEvents.PLAYER_LEVELUP, 1.0F);
	}

	/** Reacts to the ledger: a short line per transaction, fanfare for milestones. */
	public static final class LedgerEvents implements Ledger.Listener {
		@Override
		public void recorded(Tx tx) {
			Savings.markDirty();

			if (!Savings.config().chatSummaries) {
				return;
			}

			Ledger l = Savings.ledger();
			long now = System.currentTimeMillis();
			MutableComponent line = Component.literal(tx.kind.label + " ").withStyle(tx.kind.out ? ChatFormatting.RED : ChatFormatting.GREEN)
					.append(Component.literal((tx.kind.out ? "-" : "+") + Money.format(tx.amount)
							+ (tx.estimated ? " (approx.)" : "")).withStyle(ChatFormatting.WHITE));

			if (!tx.what.isBlank() && !"Balance change".equals(tx.what)) {
				line.append(Component.literal(" " + tx.what).withStyle(ChatFormatting.GRAY));
			}

			if (tx.kind.out) {
				double left = l.remainingAllowance(now);
				line.append(Component.literal(Double.isInfinite(left) ? "" : "  | allowance left: " + Money.format(left))
						.withStyle(left <= 0 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
			} else if (Savings.config().savePercent > 0 && !Savings.data().goals.isEmpty()) {
				line.append(Component.literal(String.format("  | %.0f%% set aside for goals", Savings.config().savePercent))
						.withStyle(ChatFormatting.DARK_GREEN));
			}

			send(line);
		}

		@Override
		public void goalCompleted(Goal goal) {
			Savings.markDirty();
			send(Component.literal("GOAL COMPLETE: " + goal.name + " (" + Money.format(goal.target) + ")!")
					.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
			celebrate();
		}

		@Override
		public void milestone(String id, String title, String message) {
			Savings.markDirty();
			send(Component.literal("ACHIEVEMENT: " + title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			send(Component.literal(message).withStyle(ChatFormatting.YELLOW));
			sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F);
		}
	}
}
