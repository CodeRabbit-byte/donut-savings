package com.coderabbit.donutsavings.core;

/** One recorded money movement. Amounts are always positive; the kind says which way it went. */
public final class Tx {
	public enum Kind {
		/** Auction House or shop purchase. */
		PURCHASE("Purchase", true),
		/** Sold items: /sell, shop sell, an auction that sold. */
		SALE("Sale", false),
		/** /pay to another player (including the server's tax). */
		TRANSFER_OUT("Payment sent", true),
		/** Another player paid you. */
		TRANSFER_IN("Payment received", false),
		/** The balance went down and no message explained why. */
		OTHER_OUT("Other expense", true),
		/** The balance went up and no message explained why. */
		OTHER_IN("Other income", false);

		public final String label;
		public final boolean out;

		Kind(String label, boolean out) {
			this.label = label;
			this.out = out;
		}
	}

	public long time;
	public Kind kind;
	public double amount;
	/** Item name for a purchase or sale, player name for a transfer, or a note. */
	public String what = "";
	/** "chat", "scoreboard", "menu", "manual". */
	public String source = "";
	/** True when the amount comes from an abbreviated balance rather than an exact message. */
	public boolean estimated;

	public Tx() {
	}

	public Tx(long time, Kind kind, double amount, String what, String source, boolean estimated) {
		this.time = time;
		this.kind = kind;
		this.amount = amount;
		this.what = what == null ? "" : what;
		this.source = source;
		this.estimated = estimated;
	}

	public double signed() {
		return kind.out ? -amount : amount;
	}
}
