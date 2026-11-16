package com.coderabbit.donutsavings.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.coderabbit.donutsavings.client.Notify;
import com.coderabbit.donutsavings.client.Savings;
import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Money;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Every common setting. Toggles and amounts are edited on a draft and only applied by Save, which
 * refuses to loosen anything while STRICT SAVINGS MODE is on. Strict mode itself switches on at
 * once and switches off only through {@link StrictOffScreen}.
 */
public class SettingsScreen extends Screen {
	private record Field(String label, EditBox box, Function<Config, String> read, BiConsumer<Config, String> write) {
	}

	private final Screen parent;
	private Config draft;
	private final List<Field> fields = new ArrayList<>();
	private String message = "";
	private int messageColor = Ui.DIM;
	private int left;
	private int colW;
	private int rowH;
	private int widgetH = 20;
	private int top;

	public SettingsScreen(Screen parent) {
		super(Component.literal("Savings Settings"));
		this.parent = parent;
		this.draft = Savings.config().copy();
	}

	@Override
	protected void init() {
		fields.clear();
		int w = Math.min(width - 16, 440);
		left = (width - w) / 2;
		colW = (w - 10) / 2;
		top = 46;
		// Nine rows between the strict button and the bottom bar, whatever the GUI scale.
		rowH = Math.max(15, Math.min(24, (height - top - 30) / 9));
		widgetH = Math.min(20, rowH - 1);
		Config saved = Savings.config();

		// ---- the big one
		MutableComponent strictLabel = saved.strictMode
				? Component.literal("STRICT SAVINGS MODE: ON").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
				: Component.literal("STRICT SAVINGS MODE: OFF").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
		addRenderableWidget(Button.builder(strictLabel, b -> toggleStrict()).bounds(left, 20, w, 22).build());

		// ---- toggles (left column)
		int y = top;
		boolean strict = saved.strictMode;
		y = toggle(y, "Block /pay", c -> c.blockPay, (c, v) -> c.blockPay = v, strict);
		y = toggle(y, "Purchase confirmations", c -> c.purchaseConfirmations, (c, v) -> c.purchaseConfirmations = v, strict);
		y = toggle(y, "Spending warnings", c -> c.spendingWarnings, (c, v) -> c.spendingWarnings = v, strict);
		y = toggle(y, "Enforce limits (block)", c -> c.enforceLimits, (c, v) -> c.enforceLimits = v, strict);
		y = toggle(y, "Spending lock", c -> c.spendingLock, (c, v) -> c.spendingLock = v, false);
		y = toggle(y, "Protect goal funds", c -> c.protectGoalFunds, (c, v) -> c.protectGoalFunds = v, false);
		y = toggle(y, "Count unexplained drops", c -> c.countUntrackedDrops, (c, v) -> c.countUntrackedDrops = v, false);
		y = toggle(y, "Chat summaries", c -> c.chatSummaries, (c, v) -> c.chatSummaries = v, false);
		toggle(y, "Only on DonutSMP", c -> c.onlyOnDonut, (c, v) -> c.onlyOnDonut = v, false);

		// ---- amounts (right column)
		y = top;
		y = money(y, "Daily limit", c -> c.dailyLimit, (c, v) -> c.dailyLimit = v);
		y = money(y, "Weekly limit", c -> c.weeklyLimit, (c, v) -> c.weeklyLimit = v);
		y = money(y, "Monthly limit", c -> c.monthlyLimit, (c, v) -> c.monthlyLimit = v);
		y = money(y, "Max single purchase", c -> c.maxSinglePurchase, (c, v) -> c.maxSinglePurchase = v);
		y = money(y, "Minimum balance", c -> c.minBalance, (c, v) -> c.minBalance = v);
		y = money(y, "Savings goal", c -> c.mainGoal, (c, v) -> c.mainGoal = v);
		y = money(y, "Large purchase at", c -> c.largePurchase, (c, v) -> c.largePurchase = v);
		y = number(y, "Save % of earnings", c -> c.savePercent, (c, v) -> c.savePercent = v, 0, 100);
		number(y, "Cooldown (seconds)", c -> c.unnecessaryCooldownSeconds, (c, v) -> c.unnecessaryCooldownSeconds = (int) Math.round(v), 0, 86400);

		int by = height - 26;
		int bw = Math.min(100, (w - 8) / 3);
		addRenderableWidget(Button.builder(Component.literal("Save").withStyle(ChatFormatting.GREEN), b -> save())
				.bounds(width / 2 - bw - bw / 2 - 4, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reload file"), b -> {
			Savings.reloadConfig();
			draft = Savings.config().copy();
			say("Reloaded config.json.", Ui.DIM);
			rebuildWidgets();
		}).bounds(width / 2 - bw / 2, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(width / 2 + bw / 2 + 4, by, bw, 20).build());
	}

	private int toggle(int y, String label, Predicate<Config> get, BiConsumer<Config, Boolean> set, boolean forced) {
		MutableComponent text = Component.literal(label + ": ");

		if (forced) {
			text.append(Component.literal("ON (strict)").withStyle(ChatFormatting.DARK_GREEN));
		} else {
			text.append(get.test(draft) ? Component.literal("ON").withStyle(ChatFormatting.GREEN)
					: Component.literal("OFF").withStyle(ChatFormatting.RED));
		}

		Button b = addRenderableWidget(Button.builder(text, btn -> {
			set.accept(draft, !get.test(draft));
			rebuildKeepingBoxes();
		}).bounds(left, y, colW, widgetH).build());
		b.active = !forced;
		return y + rowH;
	}

	private int money(int y, String label, Function<Config, Double> get, BiConsumer<Config, Double> set) {
		return box(y, label, c -> Money.formatInput(get.apply(c)), (c, s) -> {
			double v = s.isBlank() ? 0 : Money.parse(s);

			if (v < 0) {
				throw new IllegalArgumentException(label + ": \"" + s + "\" is not an amount (try 100m, 1.5b, 250k)");
			}

			set.accept(c, v);
		});
	}

	private int number(int y, String label, Function<Config, Number> get, BiConsumer<Config, Double> set, double min, double max) {
		return box(y, label, c -> {
			double d = get.apply(c).doubleValue();
			return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
		}, (c, s) -> {
			double v;

			try {
				v = s.isBlank() ? 0 : Double.parseDouble(s.trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(label + ": \"" + s + "\" is not a number");
			}

			if (v < min || v > max) {
				throw new IllegalArgumentException(label + " must be between " + (long) min + " and " + (long) max);
			}

			set.accept(c, v);
		});
	}

	private int box(int y, String label, Function<Config, String> read, BiConsumer<Config, String> write) {
		int bw = Math.min(90, colW / 2);
		int x = left + colW + 10;
		EditBox box = new EditBox(font, x + colW - bw, y, bw, widgetH, Component.literal(label));
		box.setMaxLength(24);
		box.setValue(read.apply(draft));
		addRenderableWidget(box);
		fields.add(new Field(label, box, read, write));
		return y + rowH;
	}

	/** Rebuild after a toggle without losing what was typed into the boxes. */
	private void rebuildKeepingBoxes() {
		List<String> typed = new ArrayList<>();

		for (Field f : fields) {
			typed.add(f.box.getValue());
		}

		rebuildWidgets();

		for (int i = 0; i < fields.size() && i < typed.size(); i++) {
			fields.get(i).box.setValue(typed.get(i));
		}
	}

	/** Applies the boxes to a copy of the draft. Throws with a readable message on bad input. */
	private Config collect() {
		Config next = draft.copy();

		for (Field f : fields) {
			f.write.accept(next, f.box.getValue().trim());
		}

		next.strictMode = Savings.config().strictMode;
		next.repair();
		return next;
	}

	private void save() {
		Config next;

		try {
			next = collect();
		} catch (IllegalArgumentException e) {
			say(e.getMessage(), Ui.RED);
			return;
		}

		Config now = Savings.config();

		if (now.strictMode) {
			String why = now.loosenedBy(next);

			if (why != null) {
				say("Strict Savings Mode: you can't " + why + ". Tighten only, or turn strict mode off first.", Ui.RED);
				Notify.denied();
				return;
			}
		}

		Savings.setConfig(next);
		draft = Savings.config().copy();
		say("Saved.", Ui.GREEN);
		rebuildWidgets();
	}

	private void toggleStrict() {
		if (Savings.config().strictMode) {
			minecraft.setScreen(new StrictOffScreen(this));
			return;
		}

		Config c = Savings.config().copy();
		c.strictMode = true;
		Savings.setConfig(c);
		draft.strictMode = true;
		Notify.good("STRICT SAVINGS MODE is ON. /pay is blocked, limits are enforced, and they can only get stricter.");
		Notify.celebrate();
		say("Strict mode on. Limits can now only be tightened.", Ui.GREEN);
		rebuildKeepingBoxes();
	}

	private void say(String s, int color) {
		message = s;
		messageColor = color;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, Ui.BG);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// The top line is the title, or the latest message (errors must be seen).
		if (!message.isEmpty()) {
			g.drawCenteredString(font, Ui.clip(font, message, width - 10), width / 2, 6, messageColor);
		} else {
			g.drawCenteredString(font, Ui.clip(font, "Savings Settings - amounts like 100m, 1.5b, 250k; 0 = no limit", width - 10),
					width / 2, 6, Ui.GOLD);
		}

		int x = left + colW + 10;

		for (Field f : fields) {
			int y = f.box.getY() + (f.box.getHeight() - 8) / 2;
			g.drawString(font, Ui.clip(font, f.label, colW - f.box.getWidth() - 6), x, y, Ui.TEXT, false);
		}

		super.render(g, mouseX, mouseY, delta);
	}
}
