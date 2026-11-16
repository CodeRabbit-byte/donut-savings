package com.coderabbit.donutsavings.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DonutSMP money: parsing "1.1M", "$ 2,000,000" and "500k", and printing it back. */
public final class Money {
	/** What the user types into a settings box: number, optional commas/decimals, optional suffix. */
	private static final Pattern INPUT = Pattern.compile(
			"^\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KkMmBbTtQq])?$");

	/**
	 * An amount inside a chat line or item lore: "$" then the number. The suffix must not be the
	 * first letter of a word, so "$ 2 tax" is 2, not 2 trillion.
	 */
	private static final Pattern IN_TEXT = Pattern.compile(
			"\\$\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s?([KkMmBbTtQq])(?![A-Za-z]))?");

	/** Lore without a dollar sign: "Price: 1.5M", "Cost 250k". */
	private static final Pattern LABELLED = Pattern.compile(
			"(?i)\\b(?:price|cost|buy(?:\\s+price)?|total)\\b\\s*[:=-]?\\s*\\$?\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s?([KkMmBbTtQq])(?![A-Za-z]))?");

	/** An amount found in text, with the smallest change its printed form can show. */
	public record Found(double value, double step, int start, int end) {
	}

	private Money() {
	}

	public static double multiplier(String suffix) {
		if (suffix == null || suffix.isEmpty()) {
			return 1;
		}

		return switch (Character.toUpperCase(suffix.charAt(0))) {
			case 'K' -> 1e3;
			case 'M' -> 1e6;
			case 'B' -> 1e9;
			case 'T' -> 1e12;
			case 'Q' -> 1e15;
			default -> 1;
		};
	}

	public static double value(String number, String suffix) {
		return Double.parseDouble(number.replace(",", "")) * multiplier(suffix);
	}

	/**
	 * The resolution of a printed amount: "1.5B" can only show changes of 0.1B, "1,234" of 1.
	 * Used to tell real balance changes from rounding on an abbreviated scoreboard.
	 */
	public static double step(String number, String suffix) {
		int dot = number.indexOf('.');
		int decimals = dot < 0 ? 0 : number.length() - dot - 1;
		return Math.pow(10, -decimals) * multiplier(suffix);
	}

	/** Parses what the user typed. Returns -1 if it is not money. */
	public static double parse(String text) {
		if (text == null) {
			return -1;
		}

		Matcher m = INPUT.matcher(text.trim());

		if (!m.matches()) {
			return -1;
		}

		try {
			return value(m.group(1), m.group(2));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Every "$X" amount in a line, in order. */
	public static List<Found> findAll(String text) {
		List<Found> out = new ArrayList<>();

		if (text == null) {
			return out;
		}

		Matcher m = IN_TEXT.matcher(text);

		while (m.find()) {
			try {
				out.add(new Found(value(m.group(1), m.group(2)), step(m.group(1), m.group(2)), m.start(), m.end()));
			} catch (NumberFormatException ignored) {
				// not a number after all
			}
		}

		return out;
	}

	/** The first "$X" in the text, or null. */
	public static Found findFirst(String text) {
		List<Found> all = findAll(text);
		return all.isEmpty() ? null : all.get(0);
	}

	/** A price in item lore: "$X" first, then "Price: X" without a dollar sign. */
	public static Found findPrice(String text) {
		Found f = findFirst(text);

		if (f != null) {
			return f;
		}

		if (text == null) {
			return null;
		}

		Matcher m = LABELLED.matcher(text);

		if (m.find()) {
			try {
				return new Found(value(m.group(1), m.group(2)), step(m.group(1), m.group(2)), m.start(), m.end());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}

		return null;
	}

	/** Short form for display: 1100000 becomes "$1.1M". */
	public static String format(double v) {
		if (Double.isNaN(v)) {
			return "?";
		}

		if (Double.isInfinite(v)) {
			return "no limit";
		}

		String[] suffixes = {"", "K", "M", "B", "T", "Q"};
		int i = 0;
		double n = Math.abs(v);

		while (n >= 1000 && i < suffixes.length - 1) {
			n /= 1000;
			i++;
		}

		BigDecimal d = BigDecimal.valueOf(n).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();

		// 999.999K rounds up to 1000K; print it as 1M instead.
		if (d.compareTo(BigDecimal.valueOf(1000)) >= 0 && i < suffixes.length - 1) {
			d = BigDecimal.ONE;
			i++;
		}

		return (v < 0 ? "-$" : "$") + d.toPlainString() + suffixes[i];
	}

	public static String formatSigned(double v) {
		return (v > 0 ? "+" : "") + format(v);
	}

	/** Lower-case short form for a settings box: "100m". */
	public static String formatInput(double v) {
		if (v <= 0) {
			return "0";
		}

		return format(v).substring(1).toLowerCase(Locale.ROOT);
	}
}
