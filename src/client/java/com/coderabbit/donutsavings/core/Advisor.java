package com.coderabbit.donutsavings.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The strict financial advisor: looks at a purchase or payment before it happens and decides
 * whether it is blocked, needs a warning, or needs an extra confirmation.
 */
public final class Advisor {
	public enum Kind {
		PURCHASE, TRANSFER
	}

	public static final String SURE = "Are you sure? You're supposed to be saving!";
	public static final String SETBACK = "This purchase will set your savings back.";
	public static final String CLOSER = "You are getting closer to wasting your money!";
	public static final String WALK_AWAY = "Walk away. You don't need this.";
	public static final String BRO = "BRO, YOU WERE SUPPOSED TO BE SAVING!";

	/** Everything the confirmation screen and chat summary show. */
	public static final class Verdict {
		public Kind kind;
		public String item;
		/** NaN when the price could not be read. */
		public double price = Double.NaN;
		/** Rules that stop the purchase outright (only when limits are enforced). */
		public final List<String> blocks = new ArrayList<>();
		/** Everything else worth saying. */
		public final List<String> warnings = new ArrayList<>();
		public boolean large;
		public boolean unnecessary;
		public String headline = SURE;

		// regret calculator
		/** NaN when the balance is unknown. */
		public double before = Double.NaN;
		public double after = Double.NaN;
		public double goal;
		/** How far this puts you back from the goal. */
		public double setBack;
		/** Percent of the goal this costs. NaN without a goal or a price. */
		public double goalPercent = Double.NaN;
		/** Remaining allowance before and after. Infinite when no limits are set. */
		public double allowanceBefore = Double.POSITIVE_INFINITY;
		public double allowanceAfter = Double.POSITIVE_INFINITY;
		/** Hours of play to earn it back. NaN when there is not enough earnings data. */
		public double earnBackHours = Double.NaN;

		public boolean blocked() {
			return !blocks.isEmpty();
		}

		public boolean priceKnown() {
			return !Double.isNaN(price);
		}
	}

	private Advisor() {
	}

	/**
	 * @param price the full amount that would leave the balance (for /pay including the tax), or NaN
	 */
	public static Verdict evaluate(Ledger ledger, Kind kind, String item, double price, long now) {
		Config c = ledger.config();
		Data d = ledger.data();
		Verdict v = new Verdict();
		v.kind = kind;
		v.item = item == null || item.isBlank() ? (kind == Kind.TRANSFER ? "Payment" : "Unknown item") : item;
		v.price = price;
		boolean enforce = c.enforces();
		List<String> hard = enforce ? v.blocks : v.warnings;
		String noun = kind == Kind.TRANSFER ? "payment" : "purchase";

		if (c.spendingLock) {
			v.blocks.add("SPENDING LOCK is on. Nothing gets bought until you turn it off.");
		}

		double rDay = ledger.remainingToday(now);
		double rWeek = ledger.remainingWeek(now);
		double rMonth = ledger.remainingMonth(now);
		v.allowanceBefore = Math.min(rDay, Math.min(rWeek, rMonth));

		if (!v.priceKnown()) {
			v.warnings.add("The price could not be read, so the limits cannot be checked exactly.");

			if (v.allowanceBefore <= 0) {
				hard.add("Your spending allowance is used up (" + which(c, rDay, rWeek, rMonth) + ").");
			}
		} else {
			v.allowanceAfter = Math.max(0, v.allowanceBefore - price);

			if (c.maxSinglePurchase > 0 && price > c.maxSinglePurchase) {
				hard.add("Costs " + Money.format(price) + ", over your maximum single " + noun + " of "
						+ Money.format(c.maxSinglePurchase) + ".");
			}

			limit(hard, "daily", c.dailyLimit, rDay, price);
			limit(hard, "weekly", c.weeklyLimit, rWeek, price);
			limit(hard, "monthly", c.monthlyLimit, rMonth, price);
		}

		double floor = ledger.floor();

		if (ledger.hasBalance()) {
			v.before = d.balance;

			if (v.priceKnown()) {
				v.after = d.balance - price;

				if (floor > 0 && v.after < floor) {
					hard.add("Leaves you with " + Money.format(v.after) + ", below your "
							+ (floor > c.minBalance ? "protected savings" : "minimum balance") + " of " + Money.format(floor) + ".");
				}
			}
		} else if (floor > 0) {
			v.warnings.add("Your balance is unknown, so the minimum balance cannot be checked. Open /bal or look at the scoreboard.");
		}

		if (kind == Kind.PURCHASE) {
			v.unnecessary = !ledger.isEssential(v.item);

			if (v.unnecessary) {
				v.warnings.add("This is not on your essentials list. Do you NEED it, or just want it?");
				long wait = c.unnecessaryCooldownSeconds * 1000L - (now - d.lastUnnecessaryPurchase);

				if (c.unnecessaryCooldownSeconds > 0 && d.lastUnnecessaryPurchase > 0 && wait > 0) {
					hard.add("Cooldown: you bought something you didn't need " + Period.duration(now - d.lastUnnecessaryPurchase)
							+ " ago. Wait " + Period.duration(wait) + ".");
				}
			}
		}

		// regret calculator
		v.goal = c.mainGoal;

		if (v.priceKnown()) {
			if (v.goal > 0) {
				v.goalPercent = price / v.goal * 100.0;
				v.setBack = ledger.hasBalance() ? Math.max(0, Math.min(price, v.goal - v.after)) : price;

				if (v.setBack > 0) {
					v.warnings.add("Sets your " + Money.format(v.goal) + " goal back by " + Money.format(v.setBack)
							+ String.format(" (%.2f%% of it).", v.goalPercent));
				}
			}

			double rate = ledger.earningsPerHour(now);

			if (!Double.isNaN(rate) && rate > 0) {
				v.earnBackHours = price / rate;
			}

			v.large = (c.largePurchase > 0 && price >= c.largePurchase)
					|| (ledger.hasBalance() && c.largePurchasePercent > 0 && d.balance > 0
					&& price >= d.balance * c.largePurchasePercent / 100.0);
		}

		double used = ledger.allowanceUsed(now);

		if (used >= 0.75) {
			v.warnings.add(String.format("You've already used %d%% of an allowance. Slow down.", Math.round(Math.min(used, 9.99) * 100)));
		}

		if (v.blocked()) {
			v.headline = BRO;
		} else if (v.large) {
			v.headline = WALK_AWAY;
		} else if (used >= 0.75) {
			v.headline = CLOSER;
		} else if (v.setBack > 0) {
			v.headline = SETBACK;
		} else {
			v.headline = SURE;
		}

		return v;
	}

	private static void limit(List<String> into, String name, double limit, double remaining, double price) {
		if (limit <= 0) {
			return;
		}

		if (remaining <= 0) {
			into.add("Your " + name + " allowance of " + Money.format(limit) + " is used up.");
		} else if (price > remaining) {
			into.add("Exceeds your remaining " + name + " allowance (" + Money.format(remaining) + " left of "
					+ Money.format(limit) + ").");
		}
	}

	private static String which(Config c, double day, double week, double month) {
		if (c.dailyLimit > 0 && day <= 0) return "daily";
		if (c.weeklyLimit > 0 && week <= 0) return "weekly";
		return "monthly";
	}

	/** "2h 15m of play", "45m of play", or null. */
	public static String earnBack(double hours) {
		if (Double.isNaN(hours) || Double.isInfinite(hours)) {
			return null;
		}

		return Period.duration((long) (hours * 3600_000L)) + " of play";
	}
}
