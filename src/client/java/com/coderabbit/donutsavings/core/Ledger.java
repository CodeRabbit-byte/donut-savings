package com.coderabbit.donutsavings.core;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** The money book: records transactions and answers every "how much" question. */
public final class Ledger {
	public static final int HISTORY_CAP = 5000;
	private static final long WEEK = 7L * 24 * 3600 * 1000;

	public interface Listener {
		default void recorded(Tx tx) {
		}

		default void goalCompleted(Goal goal) {
		}

		default void milestone(String id, String title, String message) {
		}
	}

	private final Data data;
	private final Supplier<Config> config;
	private final ZoneId zone;
	private Listener listener = new Listener() {
	};

	public Ledger(Data data, Supplier<Config> config, ZoneId zone) {
		this.data = data;
		this.config = config;
		this.zone = zone;
	}

	public void listener(Listener l) {
		this.listener = l;
	}

	public Data data() {
		return data;
	}

	public Config config() {
		return config.get();
	}

	public ZoneId zone() {
		return zone;
	}

	// ---------------------------------------------------------------- writing

	public void record(Tx tx) {
		data.history.add(tx);

		if (data.trackingSince == 0) {
			data.trackingSince = tx.time;
		}

		if (tx.kind.out) {
			data.totalSpent += tx.amount;
		} else {
			data.totalEarned += tx.amount;
			allocate(tx.amount * config.get().savePercent / 100.0);
		}

		if (tx.kind == Tx.Kind.PURCHASE && !isEssential(tx.what)) {
			data.lastUnnecessaryPurchase = Math.max(data.lastUnnecessaryPurchase, tx.time);
		}

		while (data.history.size() > HISTORY_CAP) {
			data.history.remove(0);
		}

		listener.recorded(tx);
	}

	/** Sets money aside for the goals, first unfinished goal first. */
	public void allocate(double amount) {
		double left = amount;

		for (Goal g : data.goals) {
			if (left <= 0) {
				break;
			}

			if (g.done()) {
				continue;
			}

			double take = Math.min(left, g.remaining());
			g.saved += take;
			left -= take;

			if (g.done()) {
				g.completedAt = System.currentTimeMillis();
				listener.goalCompleted(g);
			}
		}
	}

	/** A new balance reading. Checks the milestones. */
	public void balance(double value, double step, String source, long now) {
		data.balance = value;
		data.balanceStep = step;
		data.balanceAt = now;
		data.balanceSource = source;

		if (data.startBalance < 0) {
			data.startBalance = value;

			if (data.trackingSince == 0) {
				data.trackingSince = now;
			}
		}

		data.peakBalance = Math.max(data.peakBalance, value);

		for (Achievements.Milestone m : Achievements.MILESTONES) {
			if (value >= m.amount() && data.achievements.add(m.id())) {
				listener.milestone(m.id(), m.title(), m.message());
			}
		}

		double goal = config.get().mainGoal;

		if (goal > 0 && value >= goal && data.achievements.add(Achievements.MAIN_GOAL + ":" + (long) goal)) {
			listener.milestone(Achievements.MAIN_GOAL, Achievements.mainGoalTitle(goal),
					"YOU DID IT. " + Money.format(goal) + " saved. Set a bigger goal - and don't you dare blow it.");
		}
	}

	public void addPlayTime(long seconds, long now) {
		data.playSeconds.merge(Period.dayKey(now, zone), seconds, Long::sum);

		// Keep about three months of days.
		while (data.playSeconds.size() > 100) {
			String first = data.playSeconds.keySet().iterator().next();
			data.playSeconds.remove(first);
		}
	}

	// ---------------------------------------------------------------- reading

	public boolean hasBalance() {
		return data.balance >= 0;
	}

	public double balance() {
		return data.balance;
	}

	/** Money counted toward the spending limits since {@code from}. */
	public double spentSince(long from) {
		boolean other = config.get().countUntrackedDrops;
		double sum = 0;

		for (int i = data.history.size() - 1; i >= 0; i--) {
			Tx t = data.history.get(i);

			if (t.time < from) {
				break;
			}

			if (t.kind == Tx.Kind.PURCHASE || t.kind == Tx.Kind.TRANSFER_OUT || (other && t.kind == Tx.Kind.OTHER_OUT)) {
				sum += t.amount;
			}
		}

		return sum;
	}

	public double sumSince(long from, Tx.Kind kind) {
		double sum = 0;

		for (int i = data.history.size() - 1; i >= 0; i--) {
			Tx t = data.history.get(i);

			if (t.time < from) {
				break;
			}

			if (t.kind == kind) {
				sum += t.amount;
			}
		}

		return sum;
	}

	public double earnedSince(long from) {
		double sum = 0;

		for (int i = data.history.size() - 1; i >= 0; i--) {
			Tx t = data.history.get(i);

			if (t.time < from) {
				break;
			}

			if (!t.kind.out) {
				sum += t.amount;
			}
		}

		return sum;
	}

	public long dayStart(long now) {
		return Period.startOfDay(now, zone);
	}

	public long weekStart(long now) {
		return Period.startOfWeek(now, zone, config.get().firstDayOfWeek());
	}

	public long monthStart(long now) {
		return Period.startOfMonth(now, zone);
	}

	public double spentToday(long now) {
		return spentSince(dayStart(now));
	}

	public double spentThisWeek(long now) {
		return spentSince(weekStart(now));
	}

	public double spentThisMonth(long now) {
		return spentSince(monthStart(now));
	}

	public static double remaining(double limit, double spent) {
		return limit <= 0 ? Double.POSITIVE_INFINITY : Math.max(0, limit - spent);
	}

	public double remainingToday(long now) {
		return remaining(config.get().dailyLimit, spentToday(now));
	}

	public double remainingWeek(long now) {
		return remaining(config.get().weeklyLimit, spentThisWeek(now));
	}

	public double remainingMonth(long now) {
		return remaining(config.get().monthlyLimit, spentThisMonth(now));
	}

	/** The tightest of the three allowances. Infinite when no limit is set. */
	public double remainingAllowance(long now) {
		return Math.min(remainingToday(now), Math.min(remainingWeek(now), remainingMonth(now)));
	}

	/** The most-used allowance, as a fraction of its limit (0 when no limits are set). */
	public double allowanceUsed(long now) {
		Config c = config.get();
		double used = 0;

		if (c.dailyLimit > 0) used = Math.max(used, spentToday(now) / c.dailyLimit);
		if (c.weeklyLimit > 0) used = Math.max(used, spentThisWeek(now) / c.weeklyLimit);
		if (c.monthlyLimit > 0) used = Math.max(used, spentThisMonth(now) / c.monthlyLimit);

		return used;
	}

	/** Money set aside for goals. */
	public double goalFunds() {
		double sum = 0;

		for (Goal g : data.goals) {
			sum += g.saved;
		}

		return sum;
	}

	/** The balance a purchase must not take you under. */
	public double floor() {
		Config c = config.get();
		return Math.max(c.minBalance, c.protectGoalFunds ? goalFunds() : 0);
	}

	/** Earned minus spent since tracking started. */
	public double netSaved() {
		return data.totalEarned - data.totalSpent;
	}

	public long playSecondsSince(long from) {
		String fromKey = Period.dayKey(from, zone);
		long sum = 0;

		for (var e : data.playSeconds.entrySet()) {
			if (e.getKey().compareTo(fromKey) >= 0) {
				sum += e.getValue();
			}
		}

		return sum;
	}

	/**
	 * Money earned per hour played over the last 7 days, or NaN when there is not enough data
	 * (under 30 minutes played or fewer than 3 earnings).
	 */
	public double earningsPerHour(long now) {
		long from = now - WEEK;
		long seconds = playSecondsSince(from);
		int count = 0;
		double sum = 0;

		for (int i = data.history.size() - 1; i >= 0; i--) {
			Tx t = data.history.get(i);

			if (t.time < from) {
				break;
			}

			if (!t.kind.out) {
				count++;
				sum += t.amount;
			}
		}

		if (seconds < 1800 || count < 3 || sum <= 0) {
			return Double.NaN;
		}

		return sum / (seconds / 3600.0);
	}

	public boolean isEssential(String item) {
		if (item == null || item.isBlank()) {
			return false;
		}

		String lower = item.toLowerCase(Locale.ROOT);

		for (String word : config.get().essentials) {
			if (!word.isBlank() && lower.contains(word.toLowerCase(Locale.ROOT).trim())) {
				return true;
			}
		}

		return false;
	}

	public List<Tx> topPurchases(int n) {
		List<Tx> out = new ArrayList<>();

		for (Tx t : data.history) {
			if (t.kind == Tx.Kind.PURCHASE || t.kind == Tx.Kind.TRANSFER_OUT) {
				out.add(t);
			}
		}

		out.sort(Comparator.comparingDouble((Tx t) -> t.amount).reversed());
		return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
	}
}
