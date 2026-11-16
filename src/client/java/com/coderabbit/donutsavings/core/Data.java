package com.coderabbit.donutsavings.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything tracked, saved as {@code config/donutsavings/data.json}. */
public final class Data {
	/** Last known balance, or -1 if never seen. */
	public double balance = -1;
	/** Resolution of that balance as printed (0 = exact). */
	public double balanceStep;
	public long balanceAt;
	public String balanceSource = "";
	/** The first balance ever seen, and when tracking started. */
	public double startBalance = -1;
	public long trackingSince;
	/** Highest balance ever seen. */
	public double peakBalance;

	/** Running totals, so they survive the history being trimmed. */
	public double totalEarned;
	public double totalSpent;

	public List<Tx> history = new ArrayList<>();
	public List<Goal> goals = new ArrayList<>();
	/** Seconds played on the tracked server, per local day ("2026-11-16"). */
	public Map<String, Long> playSeconds = new LinkedHashMap<>();
	public Set<String> achievements = new LinkedHashSet<>();
	/** Time of the last purchase that was not on the essentials list. */
	public long lastUnnecessaryPurchase;
	/** Purchases the player walked away from, and how much that kept. */
	public int walkedAway;
	public double walkedAwayAmount;
	public int paymentsBlocked;

	public void repair() {
		if (history == null) history = new ArrayList<>();
		if (goals == null) goals = new ArrayList<>();
		if (playSeconds == null) playSeconds = new LinkedHashMap<>();
		if (achievements == null) achievements = new LinkedHashSet<>();
		if (balanceSource == null) balanceSource = "";
		history.removeIf(t -> t == null || t.kind == null);
		goals.removeIf(g -> g == null);
	}
}
