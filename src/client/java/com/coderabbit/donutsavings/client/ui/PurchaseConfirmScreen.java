package com.coderabbit.donutsavings.client.ui;

import java.util.ArrayList;
import java.util.List;

import com.coderabbit.donutsavings.client.Notify;
import com.coderabbit.donutsavings.client.PurchaseGuard;
import com.coderabbit.donutsavings.client.Savings;
import com.coderabbit.donutsavings.core.Advisor;
import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Money;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Shown in front of a held menu purchase. "Walk away" is the default and the big button; "Buy
 * anyway" unlocks after a short delay, and a large purchase needs it pressed twice. When a rule
 * blocks the purchase there is no buy button at all.
 */
public class PurchaseConfirmScreen extends Screen {
	private final PurchaseGuard.Attempt attempt;
	private final Advisor.Verdict v;
	private final long openedAt = System.currentTimeMillis();
	private long stageAt = openedAt;
	/** 0 = first confirmation, 1 = "are you REALLY sure" for a large purchase. */
	private int stage;
	private Button buy;
	private boolean decided;
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;

	public PurchaseConfirmScreen(PurchaseGuard.Attempt attempt, Advisor.Verdict verdict) {
		super(Component.literal("Purchase check"));
		this.attempt = attempt;
		this.v = verdict;
	}

	@Override
	protected void init() {
		panelW = Math.min(width - 16, 360);
		panelX = (width - panelW) / 2;
		panelH = Math.min(height - 16, 260);
		panelY = (height - panelH) / 2;
		int by = panelY + panelH - 26;

		if (v.blocked()) {
			addRenderableWidget(Button.builder(Component.literal("Walk away (blocked)").withStyle(ChatFormatting.GREEN), b -> walkAway())
					.bounds(panelX + 8, by, panelW - 16, 20).build());
			return;
		}

		int half = (panelW - 24) / 2;
		Button walk = addRenderableWidget(Button.builder(
						Component.literal("Walk away").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), b -> walkAway())
				.bounds(panelX + 8, by, half + 20, 20).build());
		buy = addRenderableWidget(Button.builder(buyLabel(), b -> pressBuy())
				.bounds(panelX + 16 + half + 20, by, panelW - 24 - half - 20, 20).build());
		buy.active = false;
		setInitialFocus(walk);
	}

	private Component buyLabel() {
		long wait = waitLeft();

		if (wait > 0) {
			return Component.literal("Buy anyway (" + (wait / 1000 + 1) + ")").withStyle(ChatFormatting.DARK_GRAY);
		}

		return stage == 0
				? Component.literal(v.large ? "Buy anyway (1/2)" : "Buy anyway").withStyle(ChatFormatting.RED)
				: Component.literal("YES, waste it (2/2)").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
	}

	private long waitLeft() {
		Config c = Savings.config();
		long delay = c.confirmDelaySeconds * 1000L * (v.large ? 2 : 1);
		return Math.max(0, delay - (System.currentTimeMillis() - stageAt));
	}

	@Override
	public void tick() {
		if (buy != null) {
			buy.active = waitLeft() <= 0;
			buy.setMessage(buyLabel());
		}
	}

	private void pressBuy() {
		if (waitLeft() > 0) {
			return;
		}

		if (v.large && stage == 0) {
			stage = 1;
			stageAt = System.currentTimeMillis();
			Notify.denied();
			tick();
			return;
		}

		decided = true;
		minecraft.setScreen(attempt.menuScreen());

		if (!PurchaseGuard.replay(attempt)) {
			Notify.warn("The menu changed while you were deciding, so nothing was clicked. Click again if you still want it.");
		}
	}

	private void walkAway() {
		decided = true;

		if (v.priceKnown()) {
			Savings.data().walkedAway++;
			Savings.data().walkedAwayAmount += v.price;
			Savings.markDirty();
			Notify.good("Good call. You kept " + Money.format(v.price) + ". (" + Savings.data().walkedAway
					+ " purchases skipped, " + Money.format(Savings.data().walkedAwayAmount) + " kept so far)");
		} else {
			Notify.good("Good call. Nothing was bought.");
		}

		minecraft.setScreen(attempt.menuScreen());
	}

	@Override
	public void onClose() {
		if (!decided) {
			walkAway();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xC0000000);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		Ui.panel(g, panelX, panelY, panelW, panelH);
		int x = panelX + 10;
		int right = panelX + panelW - 10;
		int y = panelY + 8;

		String head = stage == 1 ? "ARE YOU REALLY SURE? This is a LARGE purchase." : v.headline;
		int headColor = v.blocked() || stage == 1 ? Ui.RED : Ui.GOLD;
		for (FormattedCharSequence line : font.split(Component.literal(head).withStyle(ChatFormatting.BOLD), panelW - 20)) {
			g.drawCenteredString(font, line, panelX + panelW / 2, y, headColor);
			y += 10;
		}

		y += 4;
		int colW = (panelW - 30) / 2;
		int lx = x;
		int lr = x + colW;
		int rx = lr + 10;
		int top = y;

		Ui.row(g, font, "Item", Ui.clip(font, v.item, colW - 30), lx, lr, y, Ui.TEXT);
		y += 11;
		Ui.row(g, font, "Price", v.priceKnown() ? Money.format(v.price) : "unknown", lx, lr, y, Ui.RED);
		y += 11;
		Ui.row(g, font, "Balance", Double.isNaN(v.before) ? "unknown" : Money.format(v.before), lx, lr, y, Ui.TEXT);
		y += 11;
		Ui.row(g, font, "After", Double.isNaN(v.after) ? "?" : Money.format(v.after), lx, lr, y,
				Double.isNaN(v.after) ? Ui.DIM : v.after < Savings.ledger().floor() ? Ui.RED : Ui.TEXT);
		y += 11;
		Ui.row(g, font, "Allowance left", Money.format(v.allowanceBefore) + " > " + Money.format(v.allowanceAfter), lx, lr, y,
				v.allowanceAfter <= 0 ? Ui.RED : Ui.YELLOW);

		// regret calculator
		y = top;
		Ui.text(g, font, "Regret calculator", rx, y, Ui.GOLD);
		y += 11;
		Ui.row(g, font, "Goal", v.goal > 0 ? Money.format(v.goal) : "none", rx, right, y, Ui.TEXT);
		y += 11;
		Ui.row(g, font, "Set back by", v.priceKnown() ? Money.format(v.setBack) : "?", rx, right, y, Ui.RED);
		y += 11;
		Ui.row(g, font, "Goal lost", Double.isNaN(v.goalPercent) ? "?" : String.format("%.2f%%", v.goalPercent), rx, right, y, Ui.RED);
		y += 11;
		String earn = Advisor.earnBack(v.earnBackHours);
		Ui.row(g, font, "Earn back", earn == null ? "not enough data" : earn, rx, right, y, earn == null ? Ui.DIM : Ui.YELLOW);
		y += 11;

		if (!Double.isNaN(v.before) && v.goal > 0) {
			double frac = v.before / v.goal;
			double fracAfter = Double.isNaN(v.after) ? frac : v.after / v.goal;
			Ui.bar(g, rx, y + 1, right - rx, 6, frac, Ui.DIM);
			Ui.bar(g, rx, y + 1, right - rx, 6, fracAfter, Ui.GREEN);
		}

		y = top + 5 * 11 + 8;
		List<Component> notes = new ArrayList<>();

		for (String b : v.blocks) {
			notes.add(Component.literal("BLOCKED: " + b).withStyle(ChatFormatting.RED));
		}

		if (Savings.config().warns()) {
			for (String w : v.warnings) {
				notes.add(Component.literal("! " + w).withStyle(ChatFormatting.YELLOW));
			}
		}

		int bottom = panelY + panelH - 32;

		for (Component n : notes) {
			for (FormattedCharSequence line : font.split(n, panelW - 20)) {
				if (y > bottom - 9) {
					break;
				}

				g.drawString(font, line, x, y, Ui.TEXT, false);
				y += 10;
			}
		}

		super.render(g, mouseX, mouseY, delta);
	}
}
