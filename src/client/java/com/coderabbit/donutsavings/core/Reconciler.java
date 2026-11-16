package com.coderabbit.donutsavings.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Turns three noisy sources into one clean history without counting anything twice:
 *
 * <ul>
 * <li><b>chat messages</b> ("You paid X $5M", "sold ... $2K"): exact amount, known kind.</li>
 * <li><b>balance changes</b> (scoreboard or /bal): catch what no message explained, but on an
 * abbreviated scoreboard ("$1.5B") they are only as precise as the last printed digit.</li>
 * <li><b>purchases clicked in a menu</b>: know the item name and usually the price, but not
 * whether the server actually took the money.</li>
 * </ul>
 *
 * A message is recorded at once and then "explains" a matching balance change that arrives within
 * {@link #WINDOW} ms either side. A balance change nothing explains becomes an "other" entry after
 * the window. A menu purchase becomes a purchase when a matching balance drop or purchase message
 * arrives; with no balance source at all, it is recorded on trust when it expires.
 */
public final class Reconciler {
	public static final long WINDOW = 12_000;
	public static final long EXPECT_WINDOW = 25_000;

	private record Delta(double amount, double step, long time) {
	}

	private record Explained(boolean out, double amount, long time) {
	}

	private record Expectation(String item, double price, Tx.Kind kind, long time) {
	}

	private final Ledger ledger;
	private final List<Delta> deltas = new ArrayList<>();
	private final List<Explained> explained = new ArrayList<>();
	private final List<Expectation> expected = new ArrayList<>();
	/** The last balance reading and its precision, for working out changes. */
	private double last = -1;
	private double lastStep;
	private long lastReadingAt;

	public Reconciler(Ledger ledger) {
		this.ledger = ledger;
	}

	/** Forget pending matches (disconnect). The balance itself stays in the ledger. */
	public void reset() {
		deltas.clear();
		explained.clear();
		expected.clear();
		last = -1;
	}

	/** True when a balance reading arrived recently, so balance changes can be relied on. */
	public boolean balanceLive(long now) {
		return last >= 0 && now - lastReadingAt < 120_000;
	}

	// ---------------------------------------------------------------- inputs

	/** An exact amount from a chat message. */
	public void message(Tx.Kind kind, double amount, String what, long now) {
		if (amount <= 0) {
			return;
		}

		String name = what;

		if (kind == Tx.Kind.PURCHASE) {
			Expectation e = takeExpectation(amount, now);

			if (e != null && (name == null || name.isBlank())) {
				name = e.item;
			}
		} else if (kind == Tx.Kind.TRANSFER_OUT) {
			takeExpectationOfKind(Tx.Kind.TRANSFER_OUT, amount, now);
		}

		ledger.record(new Tx(now, kind, amount, name, "chat", false));

		// Does a balance change that already arrived belong to this message?
		for (Iterator<Delta> it = deltas.iterator(); it.hasNext(); ) {
			Delta d = it.next();

			if ((d.amount < 0) == kind.out && close(Math.abs(d.amount), amount, d.step)) {
				it.remove();
				return;
			}
		}

		explained.add(new Explained(kind.out, amount, now));
	}

	/** A purchase the player confirmed in a menu. {@code price} may be NaN. */
	public void expect(String item, double price, Tx.Kind kind, long now) {
		expected.add(new Expectation(item, price, kind, now));
	}

	/** A balance reading. {@code step} is the printed precision (0 for exact). */
	public void balance(double value, double step, String source, long now) {
		double prev = last;
		double prevStep = lastStep;
		last = value;
		lastStep = step;
		lastReadingAt = now;
		ledger.balance(value, step, source, now);

		if (prev < 0) {
			return;
		}

		double delta = value - prev;
		double s = Math.max(step, prevStep);

		// An abbreviated number flickers by one digit through rounding alone.
		if (Math.abs(delta) < 2 * s || Math.abs(delta) < 0.005) {
			return;
		}

		for (Iterator<Explained> it = explained.iterator(); it.hasNext(); ) {
			Explained e = it.next();

			if (e.out == (delta < 0) && close(Math.abs(delta), e.amount, s)) {
				it.remove();
				return;
			}
		}

		if (delta < 0) {
			Expectation e = takeExpectation(-delta, now, s);

			if (e != null) {
				Tx.Kind kind = e.kind == null ? Tx.Kind.PURCHASE : e.kind;
				ledger.record(new Tx(now, kind, -delta, e.item, "menu", s > 0));
				return;
			}
		}

		deltas.add(new Delta(delta, s, now));
	}

	/** Call about once a second. */
	public void tick(long now) {
		for (Iterator<Delta> it = deltas.iterator(); it.hasNext(); ) {
			Delta d = it.next();

			if (now - d.time > WINDOW) {
				it.remove();
				Tx.Kind kind = d.amount < 0 ? Tx.Kind.OTHER_OUT : Tx.Kind.OTHER_IN;
				ledger.record(new Tx(d.time, kind, Math.abs(d.amount), "Balance change", "balance", d.step > 0));
			}
		}

		explained.removeIf(e -> now - e.time > WINDOW);

		for (Iterator<Expectation> it = expected.iterator(); it.hasNext(); ) {
			Expectation e = it.next();

			if (now - e.time > EXPECT_WINDOW) {
				it.remove();

				// No balance to check against: trust the click. With a balance, no drop means the
				// server did not go through with it (sold out, not enough money, cancelled).
				if (!balanceLive(now) && !Double.isNaN(e.price) && e.price > 0) {
					Tx.Kind kind = e.kind == null ? Tx.Kind.PURCHASE : e.kind;
					ledger.record(new Tx(e.time, kind, e.price, e.item, "menu", true));
				}
			}
		}
	}

	public int pending() {
		return deltas.size() + expected.size();
	}

	// ---------------------------------------------------------------- matching

	private Expectation takeExpectation(double amount, long now) {
		return takeExpectation(amount, now, 0, false);
	}

	private Expectation takeExpectation(double amount, long now, double step) {
		return takeExpectation(amount, now, step, true);
	}

	private Expectation takeExpectation(double amount, long now, double step, boolean anyKind) {
		Expectation best = null;

		for (Expectation e : expected) {
			if (!anyKind && e.kind != null && e.kind != Tx.Kind.PURCHASE) {
				continue;
			}

			if (now - e.time > EXPECT_WINDOW) {
				continue;
			}

			// An unknown price matches any drop; a known one must be close.
			if (Double.isNaN(e.price) || close(amount, e.price, step)) {
				best = e;
				break;
			}
		}

		if (best != null) {
			expected.remove(best);
		}

		return best;
	}

	private void takeExpectationOfKind(Tx.Kind kind, double amount, long now) {
		for (Iterator<Expectation> it = expected.iterator(); it.hasNext(); ) {
			Expectation e = it.next();

			if (e.kind == kind && (Double.isNaN(e.price) || close(amount, e.price, 0))) {
				it.remove();
				return;
			}
		}
	}

	/** Two amounts are the same transaction if they agree within the printed precision or 1%. */
	static boolean close(double a, double b, double step) {
		double tol = Math.max(Math.max(2 * step, 0.01 * Math.max(a, b)), 0.01);
		return Math.abs(a - b) <= tol;
	}
}
