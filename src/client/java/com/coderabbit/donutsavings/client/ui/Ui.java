package com.coderabbit.donutsavings.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared colours and drawing helpers. Colours are ARGB with full alpha (0 alpha draws nothing). */
public final class Ui {
	public static final int BG = 0xFF101418;
	public static final int PANEL = 0xFF1B2128;
	public static final int PANEL_EDGE = 0xFF2E3742;
	public static final int TEXT = 0xFFE8ECF0;
	public static final int DIM = 0xFF8A96A3;
	public static final int GOLD = 0xFFF2C14E;
	public static final int GREEN = 0xFF5BD68A;
	public static final int RED = 0xFFFF5C5C;
	public static final int YELLOW = 0xFFFFD25C;
	public static final int BLUE = 0xFF6CB4FF;
	public static final int BAR_BG = 0xFF0C0F12;

	private Ui() {
	}

	public static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL_EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
	}

	/** A progress bar; {@code fraction} is clamped to 0..1. */
	public static void bar(GuiGraphics g, int x, int y, int w, int h, double fraction, int color) {
		double f = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
		g.fill(x, y, x + w, y + h, PANEL_EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, BAR_BG);
		int fill = (int) Math.round((w - 2) * f);

		if (fill > 0) {
			g.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, color);
		}
	}

	/** Green while there is room, yellow past 75%, red when used up. */
	public static int usageColor(double used) {
		if (used >= 1) return RED;
		if (used >= 0.75) return YELLOW;
		return GREEN;
	}

	public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
		g.drawString(font, s, x, y, color, false);
	}

	/** Label on the left, value right-aligned at {@code right}. */
	public static void row(GuiGraphics g, Font font, String label, String value, int x, int right, int y, int valueColor) {
		g.drawString(font, label, x, y, DIM, false);
		g.drawString(font, value, right - font.width(value), y, valueColor, false);
	}

	public static String clip(Font font, String s, int width) {
		if (font.width(s) <= width) {
			return s;
		}

		return font.plainSubstrByWidth(s, Math.max(0, width - font.width("..."))) + "...";
	}
}
