package com.coderabbit.donutsavings.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads money out of server messages. The patterns only recognise <i>which kind</i> of line it is;
 * the amount is always the first "$X" after the match, so a pattern does not need number groups.
 *
 * <p>The /pay lines were seen live on DonutSMP ("NAME paid you $X." and "You paid NAME $90 (+$2
 * tax)"). The shop, auction and /bal patterns are best guesses that can be edited in the config.
 */
public final class ChatParser {
	public enum Type {
		BALANCE, PAY_SENT, PAY_RECEIVED, BOUGHT, SOLD
	}

	public record Result(Type type, double amount, double step, String who) {
	}

	private static final Pattern TAX = Pattern.compile("(?i)\\(\\s*\\+?\\s*\\$\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s?([KkMmBbTtQq])(?![A-Za-z]))?\\s*tax\\s*\\)");
	/** Words that mean a "you have $X" line is about a payment, not the balance. */
	private static final Pattern NOT_BALANCE = Pattern.compile("(?i)\\b(?:paid|received|sent|sold|bought|purchased|earned|won|lost|tax)\\b");
	private static final Map<String, Pattern> CACHE = new HashMap<>();

	private ChatParser() {
	}

	public static Pattern compile(String regex) {
		if (regex == null || regex.isBlank()) {
			return null;
		}

		synchronized (CACHE) {
			if (CACHE.containsKey(regex)) {
				return CACHE.get(regex);
			}

			Pattern p;

			try {
				p = Pattern.compile(regex);
			} catch (PatternSyntaxException e) {
				p = null;
			}

			CACHE.put(regex, p);
			return p;
		}
	}

	/** Returns null if the pattern is blank or invalid. */
	public static Matcher match(String regex, String text) {
		Pattern p = compile(regex);

		if (p == null) {
			return null;
		}

		Matcher m = p.matcher(text);
		return m.find() ? m : null;
	}

	/** @param line plain text from {@link Text#normalize} */
	public static Result parse(Config c, String line) {
		if (line == null || line.isEmpty() || line.indexOf('$') < 0) {
			return null;
		}

		Matcher m = match(c.chatPaySent, line);

		if (m != null) {
			Money.Found f = Money.findFirst(line.substring(m.end() - 1));

			if (f != null) {
				double tax = 0;
				Matcher t = TAX.matcher(line);

				if (t.find()) {
					tax = Money.value(t.group(1), t.group(2));
				}

				return new Result(Type.PAY_SENT, f.value() + tax, f.step(), group(m));
			}
		}

		m = match(c.chatPayReceived, line);

		if (m != null) {
			Money.Found f = Money.findFirst(line.substring(m.end() - 1));

			if (f != null) {
				return new Result(Type.PAY_RECEIVED, f.value(), f.step(), group(m));
			}
		}

		// Shop and auction lines are not anchored, so a player typing "I sold it for $5" could
		// trigger them. Skip anything where the keyword comes after a "name: " chat prefix.
		m = match(c.chatBought, line);

		if (m != null && !looksLikeChat(line, m.start())) {
			Money.Found f = lastBefore(line, m.end());

			if (f != null) {
				return new Result(Type.BOUGHT, f.value(), f.step(), itemName(line, m.start()));
			}
		}

		m = match(c.chatSold, line);

		if (m != null && !looksLikeChat(line, m.start())) {
			Money.Found f = lastBefore(line, m.end());

			if (f != null) {
				return new Result(Type.SOLD, f.value(), f.step(), itemName(line, m.start()));
			}
		}

		m = match(c.chatBalance, line);

		if (m != null && !looksLikeChat(line, m.start()) && !NOT_BALANCE.matcher(line).find()) {
			Money.Found f = Money.findFirst(line.substring(Math.max(0, m.end() - 1)));

			if (f != null) {
				return new Result(Type.BALANCE, f.value(), f.step(), null);
			}
		}

		return null;
	}

	/** The amount the match's trailing "$" points at, falling back to the first in the line. */
	private static Money.Found lastBefore(String line, int matchEnd) {
		List<Money.Found> all = Money.findAll(line);

		for (Money.Found f : all) {
			if (f.start() >= matchEnd - 1) {
				return f;
			}
		}

		return all.isEmpty() ? null : all.get(all.size() - 1);
	}

	private static String group(Matcher m) {
		return m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : "";
	}

	/** "Steve: I bought it for $5" - the keyword sits after a short "name:" prefix. */
	static boolean looksLikeChat(String line, int keywordAt) {
		int colon = line.indexOf(": ");
		int arrow = line.indexOf(" » ");
		int gt = line.indexOf("> ");
		int cut = min(colon, min(arrow, gt));

		if (cut < 0 || cut > 40) {
			return false;
		}

		String prefix = line.substring(0, cut);

		// A real server message may contain a colon itself, after the keyword ("Sold 64 items: $5").
		return keywordAt > cut && !prefix.contains("$");
	}

	private static int min(int a, int b) {
		if (a < 0) return b;
		if (b < 0) return a;
		return Math.min(a, b);
	}

	/** Best effort: the words between the keyword and the amount ("You bought 16x Diamond for $5"). */
	private static String itemName(String line, int keywordAt) {
		String rest = line.substring(keywordAt);
		int dollar = rest.indexOf('$');

		if (dollar < 0) {
			return "";
		}

		String s = rest.substring(0, dollar)
				.replaceFirst("(?i)^(?:you\\s+)?(?:have\\s+)?(?:bought|purchased|sold)\\s*", "")
				.replaceAll("(?i)\\s+(?:for|from|to|at)\\s*$", "")
				.replaceAll("[:\\-!]+\\s*$", "")
				.trim();
		return s.length() > 48 ? s.substring(0, 48) : s;
	}

	/** The first amount after a scoreboard line's balance label, with or without a dollar sign. */
	public static Money.Found scoreboardBalance(Config c, String line) {
		Matcher m = match(c.scoreboardBalance, line);

		if (m == null) {
			return null;
		}

		String rest = line.substring(m.end());
		Money.Found f = Money.findFirst(rest);

		if (f != null) {
			return f;
		}

		Matcher n = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s?([KkMmBbTtQq])(?![A-Za-z]))?").matcher(rest);

		if (n.find()) {
			try {
				return new Money.Found(Money.value(n.group(1), n.group(2)), Money.step(n.group(1), n.group(2)), n.start(), n.end());
			} catch (NumberFormatException e) {
				return null;
			}
		}

		return null;
	}

	/** Strips a leading "/" and a "plugin:" namespace: "/essentials:pay" becomes "pay". */
	public static String commandName(String command) {
		String s = command.trim();

		if (s.startsWith("/")) {
			s = s.substring(1);
		}

		int space = s.indexOf(' ');
		String first = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
		int colon = first.indexOf(':');
		return colon >= 0 ? first.substring(colon + 1) : first;
	}
}
