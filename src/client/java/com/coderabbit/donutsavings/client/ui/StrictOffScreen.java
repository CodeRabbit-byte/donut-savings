package com.coderabbit.donutsavings.client.ui;

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
import net.minecraft.util.FormattedCharSequence;

/**
 * Turning STRICT SAVINGS MODE off is deliberately slow: type a sentence exactly, then wait out a
 * countdown. Nothing on this screen can be done by an accidental click.
 */
public class StrictOffScreen extends Screen {
	public static final String PHRASE = "I want to stop saving";
	private static final long WAIT = 15_000;

	private final Screen parent;
	private final long openedAt = System.currentTimeMillis();
	private EditBox box;
	private Button off;

	public StrictOffScreen(Screen parent) {
		super(Component.literal("Turn off Strict Savings Mode?"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int w = Math.min(260, width - 20);
		int x = (width - w) / 2;
		int y = height / 2 + 4;
		box = new EditBox(font, x, y, w, 20, Component.literal("phrase"));
		box.setMaxLength(64);
		box.setHint(Component.literal(PHRASE).withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(box);

		addRenderableWidget(Button.builder(Component.literal("Keep saving (stay strict)").withStyle(ChatFormatting.GREEN), b -> onClose())
				.bounds(x, y + 28, w, 20).build());
		off = addRenderableWidget(Button.builder(Component.literal("Turn strict mode off"), b -> turnOff())
				.bounds(x, y + 52, w, 20).build());
		off.active = false;
	}

	private long left() {
		return Math.max(0, WAIT - (System.currentTimeMillis() - openedAt));
	}

	@Override
	public void tick() {
		boolean typed = box.getValue().trim().equalsIgnoreCase(PHRASE);
		long left = left();
		off.active = typed && left == 0;
		off.setMessage(left > 0
				? Component.literal("Wait " + (left / 1000 + 1) + "s...").withStyle(ChatFormatting.DARK_GRAY)
				: typed ? Component.literal("Turn strict mode off").withStyle(ChatFormatting.RED)
				: Component.literal("Type the sentence first").withStyle(ChatFormatting.DARK_GRAY));
	}

	private void turnOff() {
		if (!off.active) {
			return;
		}

		Config c = Savings.config().copy();
		c.strictMode = false;
		Savings.setConfig(c);
		Notify.warn("Strict Savings Mode is OFF. Your limits still apply. Don't make me regret this.");
		Notify.denied();
		minecraft.setScreen(parent);
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
		g.fill(0, 0, width, height, 0xE0200A0A);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int y = Math.max(6, height / 2 - 90);
		g.drawCenteredString(font, Component.literal("TURN OFF STRICT SAVINGS MODE?").withStyle(ChatFormatting.BOLD), width / 2, y, Ui.RED);
		y += 16;
		double bal = Savings.data().balance;
		String[] lines = {
				"Strict mode is what stops you wasting money.",
				"Without it: /pay can be unblocked, limits can be raised, and blocks become warnings if you turn enforcement off.",
				bal >= 0 && Savings.config().mainGoal > 0
						? "You are " + Money.format(Math.max(0, Savings.config().mainGoal - bal)) + " away from your goal. Is it worth it?"
						: "Is it really worth it?",
				"Type \"" + PHRASE + "\" below to continue."
		};

		for (String s : lines) {
			for (FormattedCharSequence line : font.split(Component.literal(s), Math.min(320, width - 20))) {
				g.drawCenteredString(font, line, width / 2, y, Ui.TEXT);
				y += 10;
			}
		}

		super.render(g, mouseX, mouseY, delta);
	}
}
