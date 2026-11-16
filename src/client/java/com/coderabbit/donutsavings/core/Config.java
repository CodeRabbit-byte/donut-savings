package com.coderabbit.donutsavings.core;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every setting. Saved as {@code config/donutsavings/config.json}; the settings screen edits the
 * common ones and the rest (patterns, keywords) can be edited in the file, then {@code /savings reload}.
 *
 * Amounts of 0 mean "no limit" / "off".
 */
public final class Config {
	// ---------------------------------------------------------------- where it applies
	/** Only track and protect on servers whose address contains {@link #serverFilter}. */
	public boolean onlyOnDonut = true;
	public String serverFilter = "donutsmp";

	// ---------------------------------------------------------------- protection
	/** STRICT SAVINGS MODE: forces every protection on and only lets limits get tighter. */
	public boolean strictMode = false;
	public boolean blockPay = true;
	public boolean purchaseConfirmations = true;
	public boolean spendingWarnings = true;
	/** Block (not just warn about) purchases that break a limit. Strict mode forces this on. */
	public boolean enforceLimits = true;
	/** Blocks every purchase the mod can see, whatever the limits say. */
	public boolean spendingLock = false;
	/** Treat money set aside for goals like the minimum balance. */
	public boolean protectGoalFunds = true;
	/** Count unexplained balance drops toward the spending limits. */
	public boolean countUntrackedDrops = true;
	public boolean chatSummaries = true;
	public boolean sounds = true;

	// ---------------------------------------------------------------- limits
	public double dailyLimit = 100e6;
	public double weeklyLimit = 500e6;
	public double monthlyLimit = 1e9;
	public double maxSinglePurchase = 50e6;
	public double minBalance = 0;
	/** A purchase at or above this needs a second confirmation. */
	public double largePurchase = 10e6;
	/** ...and so does one costing at least this percent of the balance. */
	public double largePurchasePercent = 10;
	/** Seconds that must pass between two purchases that are not on the essentials list. */
	public int unnecessaryCooldownSeconds = 300;
	/** Seconds the "Buy anyway" button stays disabled. */
	public int confirmDelaySeconds = 3;
	public String weekStarts = "MONDAY";

	// ---------------------------------------------------------------- saving
	public double mainGoal = 10e9;
	/** Percent of every earning that gets set aside for the goals. */
	public double savePercent = 20;
	/** Item names containing any of these words count as necessary purchases. */
	public List<String> essentials = new ArrayList<>(List.of("totem", "golden apple", "golden carrot", "steak",
			"ender pearl", "firework"));

	// ---------------------------------------------------------------- detection
	/** Commands treated as payments. "ns:pay" forms are matched too. */
	public List<String> payCommands = new ArrayList<>(List.of("pay"));
	/** Donut's /pay tax, paid by the sender on top (seen live: "You paid NAME $90 (+$2 tax)"). */
	public double payTaxPercent = 2;
	/** A scoreboard line holding the balance. The first "$X" or number after the match is read. */
	public String scoreboardBalance = "(?i)\\b(?:money|balance|bal|purse)\\b";
	/** A chat line holding the balance (e.g. the /bal reply). */
	public String chatBalance = "(?i)\\b(?:your balance|balance|you have)\\b[^$]{0,30}\\$";
	public String chatPaySent = "(?i)^you paid (\\S+) \\$";
	public String chatPayReceived = "(?i)^(\\S+) paid you \\$";
	public String chatBought = "(?i)\\b(?:bought|purchased)\\b.*?\\$";
	public String chatSold = "(?i)\\b(?:sold)\\b.*?\\$";
	/** A menu item that completes a purchase when clicked. */
	public String buyButton = "(?i)\\b(?:confirm|buy|purchase|accept|pay)\\b";
	/** ...unless its name also matches this. */
	public String notBuyButton = "(?i)\\b(?:cancel|back|close|deny|decline|sell|return|exit|previous|next|page|search|sort|filter|refresh)\\b";
	/** Lore that makes clicking an item buy it straight away. */
	public String clickToBuyLore = "(?i)click to (?:buy|purchase)";
	/** Menu titles that are shops or auction houses (for remembering what was looked at). */
	public String marketTitle = "(?i)auction|\\bah\\b|shop|market|buy|purchase|confirm|order|store";

	/** Write raw chat, scoreboard and menu text to config/donutsavings/debug.log. */
	public boolean debugLog = false;

	// ---------------------------------------------------------------- effective switches

	public boolean blocksPay() {
		return strictMode || blockPay;
	}

	public boolean confirms() {
		return strictMode || purchaseConfirmations;
	}

	public boolean warns() {
		return strictMode || spendingWarnings;
	}

	public boolean enforces() {
		return strictMode || enforceLimits;
	}

	public DayOfWeek firstDayOfWeek() {
		try {
			return DayOfWeek.valueOf(weekStarts.trim().toUpperCase(Locale.ROOT));
		} catch (RuntimeException e) {
			return DayOfWeek.MONDAY;
		}
	}

	/** Fills in anything a hand-edited or older file left out. */
	public void repair() {
		Config d = new Config();

		if (serverFilter == null) serverFilter = d.serverFilter;
		if (weekStarts == null) weekStarts = d.weekStarts;
		if (essentials == null) essentials = d.essentials;
		if (payCommands == null || payCommands.isEmpty()) payCommands = d.payCommands;
		if (scoreboardBalance == null) scoreboardBalance = d.scoreboardBalance;
		if (chatBalance == null) chatBalance = d.chatBalance;
		if (chatPaySent == null) chatPaySent = d.chatPaySent;
		if (chatPayReceived == null) chatPayReceived = d.chatPayReceived;
		if (chatBought == null) chatBought = d.chatBought;
		if (chatSold == null) chatSold = d.chatSold;
		if (buyButton == null) buyButton = d.buyButton;
		if (notBuyButton == null) notBuyButton = d.notBuyButton;
		if (clickToBuyLore == null) clickToBuyLore = d.clickToBuyLore;
		if (marketTitle == null) marketTitle = d.marketTitle;

		dailyLimit = Math.max(0, dailyLimit);
		weeklyLimit = Math.max(0, weeklyLimit);
		monthlyLimit = Math.max(0, monthlyLimit);
		maxSinglePurchase = Math.max(0, maxSinglePurchase);
		minBalance = Math.max(0, minBalance);
		largePurchase = Math.max(0, largePurchase);
		largePurchasePercent = Math.max(0, Math.min(100, largePurchasePercent));
		mainGoal = Math.max(0, mainGoal);
		savePercent = Math.max(0, Math.min(100, savePercent));
		payTaxPercent = Math.max(0, Math.min(50, payTaxPercent));
		unnecessaryCooldownSeconds = Math.max(0, unnecessaryCooldownSeconds);
		confirmDelaySeconds = Math.max(0, Math.min(30, confirmDelaySeconds));
	}

	public Config copy() {
		Config c = new Config();
		c.onlyOnDonut = onlyOnDonut;
		c.serverFilter = serverFilter;
		c.strictMode = strictMode;
		c.blockPay = blockPay;
		c.purchaseConfirmations = purchaseConfirmations;
		c.spendingWarnings = spendingWarnings;
		c.enforceLimits = enforceLimits;
		c.spendingLock = spendingLock;
		c.protectGoalFunds = protectGoalFunds;
		c.countUntrackedDrops = countUntrackedDrops;
		c.chatSummaries = chatSummaries;
		c.sounds = sounds;
		c.dailyLimit = dailyLimit;
		c.weeklyLimit = weeklyLimit;
		c.monthlyLimit = monthlyLimit;
		c.maxSinglePurchase = maxSinglePurchase;
		c.minBalance = minBalance;
		c.largePurchase = largePurchase;
		c.largePurchasePercent = largePurchasePercent;
		c.unnecessaryCooldownSeconds = unnecessaryCooldownSeconds;
		c.confirmDelaySeconds = confirmDelaySeconds;
		c.weekStarts = weekStarts;
		c.mainGoal = mainGoal;
		c.savePercent = savePercent;
		c.essentials = new ArrayList<>(essentials);
		c.payCommands = new ArrayList<>(payCommands);
		c.payTaxPercent = payTaxPercent;
		c.scoreboardBalance = scoreboardBalance;
		c.chatBalance = chatBalance;
		c.chatPaySent = chatPaySent;
		c.chatPayReceived = chatPayReceived;
		c.chatBought = chatBought;
		c.chatSold = chatSold;
		c.buyButton = buyButton;
		c.notBuyButton = notBuyButton;
		c.clickToBuyLore = clickToBuyLore;
		c.marketTitle = marketTitle;
		c.debugLog = debugLog;
		return c;
	}

	/**
	 * Under strict mode a change may only make things stricter. Returns why {@code next} is looser
	 * than this config, or null when it is not. Turning strict mode itself off is handled by its own
	 * confirmation screen, so it is not checked here.
	 */
	public String loosenedBy(Config next) {
		if (looser(next.dailyLimit, dailyLimit)) return "raise the daily limit";
		if (looser(next.weeklyLimit, weeklyLimit)) return "raise the weekly limit";
		if (looser(next.monthlyLimit, monthlyLimit)) return "raise the monthly limit";
		if (looser(next.maxSinglePurchase, maxSinglePurchase)) return "raise the maximum single purchase";
		if (next.minBalance < minBalance) return "lower the minimum balance";
		if (looser(next.largePurchase, largePurchase)) return "raise the large-purchase threshold";
		if (next.unnecessaryCooldownSeconds < unnecessaryCooldownSeconds) return "shorten the purchase cooldown";
		if (next.savePercent < savePercent) return "lower the savings percentage";
		if (spendingLock && !next.spendingLock) return "turn the spending lock off";
		if (protectGoalFunds && !next.protectGoalFunds) return "unprotect goal funds";
		if (onlyOnDonut == next.onlyOnDonut && !serverFilter.equals(next.serverFilter)) return "change the server filter";
		return null;
	}

	/** For a limit where 0 means none: is {@code next} more permissive than {@code now}? */
	private static boolean looser(double next, double now) {
		if (now <= 0) {
			return false; // already unlimited
		}

		return next <= 0 || next > now;
	}
}
