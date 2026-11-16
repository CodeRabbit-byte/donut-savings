package com.coderabbit.donutsavings.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class CoreTest {
	private static final ZoneId UTC = ZoneOffset.UTC;
	/** Monday 2026-11-16 12:00 UTC. */
	private static final long NOW = LocalDateTime.of(2026, 11, 16, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();

	private static Ledger ledger(Config c, Data d) {
		return new Ledger(d, () -> c, UTC);
	}

	// ---------------------------------------------------------------- money

	@Test
	void parsesAndFormatsMoney() {
		assertEquals(1.5e9, Money.parse("1.5b"));
		assertEquals(250e3, Money.parse("$250K"));
		assertEquals(2_000_000, Money.parse("2,000,000"));
		assertEquals(-1, Money.parse("lots"));
		assertEquals("$1.1M", Money.format(1.1e6));
		assertEquals("$1M", Money.format(999_999.999));
		assertEquals("-$5K", Money.format(-5000));
		assertEquals("100m", Money.formatInput(100e6));
	}

	@Test
	void findsAmountsInText() {
		Money.Found f = Money.findFirst("You paid Steve $ 90 (+$ 2 tax)");
		assertNotNull(f);
		assertEquals(90, f.value());
		// "t" of "tax" is not a trillion suffix
		assertEquals(2, Money.findAll("You paid Steve $ 90 (+$ 2 tax)").get(1).value());
		assertEquals(0.1e9, Money.findFirst("Money $1.5B").step(), 1e-6);
		assertEquals(1.5e6, Money.findPrice("Price: 1.5M").value());
	}

	@Test
	void normalizesSmallCaps() {
		assertEquals("money $1.5b", Text.normalize("§aᴍᴏɴᴇʏ  $1.5b"));
	}

	// ---------------------------------------------------------------- chat

	@Test
	void parsesDonutPayLines() {
		Config c = new Config();
		ChatParser.Result sent = ChatParser.parse(c, "You paid Steve $90 (+$2 tax)");
		assertEquals(ChatParser.Type.PAY_SENT, sent.type());
		assertEquals(92, sent.amount(), 1e-9);
		assertEquals("Steve", sent.who());

		ChatParser.Result got = ChatParser.parse(c, ".Bedrock_1 paid you $1.1M.");
		assertEquals(ChatParser.Type.PAY_RECEIVED, got.type());
		assertEquals(1.1e6, got.amount(), 1e-6);
	}

	@Test
	void ignoresPlayerChatThatLooksLikeASale() {
		Config c = new Config();
		assertNull(ChatParser.parse(c, "Steve: I sold my sword for $5B lol"));
		ChatParser.Result r = ChatParser.parse(c, "You sold 64x Diamond for $12.5K");
		assertEquals(ChatParser.Type.SOLD, r.type());
		assertEquals(12_500, r.amount());
		assertEquals("64x Diamond", r.who());
	}

	@Test
	void balanceLineIsNotAPayment() {
		Config c = new Config();
		ChatParser.Result r = ChatParser.parse(c, "Your balance is $3.25B");
		assertEquals(ChatParser.Type.BALANCE, r.type());
		assertEquals(3.25e9, r.amount(), 1);
		assertNull(ChatParser.parse(c, "You have received $5M"));
	}

	@Test
	void readsScoreboardMoney() {
		Config c = new Config();
		assertEquals(12.3e6, ChatParser.scoreboardBalance(c, "money $12.3m").value(), 1e-3);
		assertEquals(4e9, ChatParser.scoreboardBalance(c, "$ Money 4B").value(), 1e-3);
		assertNull(ChatParser.scoreboardBalance(c, "Kills 12"));
		assertEquals("pay", ChatParser.commandName("/essentials:pay Steve 5m"));
	}

	// ---------------------------------------------------------------- budgets and advisor

	@Test
	void limitsBlockWhenEnforced() {
		Config c = new Config();
		Data d = new Data();
		Ledger l = ledger(c, d);
		l.balance(6e9, 0, "test", NOW);
		c.minBalance = 5e9;
		l.record(new Tx(NOW - 1000, Tx.Kind.PURCHASE, 80e6, "Diamond", "test", false));

		Advisor.Verdict v = Advisor.evaluate(l, Advisor.Kind.PURCHASE, "Elytra", 30e6, NOW);
		assertTrue(v.blocked(), "30M over the 20M left today must block");
		assertEquals(Advisor.BRO, v.headline);

		c.enforceLimits = false;
		v = Advisor.evaluate(l, Advisor.Kind.PURCHASE, "Elytra", 30e6, NOW);
		assertFalse(v.blocked(), "without enforcement it is only a warning");
		assertFalse(v.warnings.isEmpty());

		c.strictMode = true;
		assertTrue(Advisor.evaluate(l, Advisor.Kind.PURCHASE, "Elytra", 30e6, NOW).blocked(), "strict forces enforcement");
	}

	@Test
	void minimumBalanceAndRegretCalculator() {
		Config c = new Config();
		c.dailyLimit = c.weeklyLimit = c.monthlyLimit = c.maxSinglePurchase = 0;
		c.mainGoal = 10e9;
		c.minBalance = 9.6e9;
		Data d = new Data();
		Ledger l = ledger(c, d);
		l.balance(10e9, 0, "test", NOW);

		Advisor.Verdict v = Advisor.evaluate(l, Advisor.Kind.PURCHASE, "Beacon", 500e6, NOW);
		assertEquals(10e9, v.before);
		assertEquals(9.5e9, v.after);
		assertEquals(500e6, v.setBack);
		assertEquals(5.0, v.goalPercent, 1e-9);
		assertTrue(v.blocked(), "9.5B is under the 9.6B minimum");
		assertTrue(v.large);
	}

	@Test
	void cooldownOnlyForUnnecessaryPurchases() {
		Config c = new Config();
		c.dailyLimit = c.weeklyLimit = c.monthlyLimit = c.maxSinglePurchase = 0;
		Data d = new Data();
		Ledger l = ledger(c, d);
		l.record(new Tx(NOW - 60_000, Tx.Kind.PURCHASE, 1e6, "Fancy Hat", "test", false));

		assertTrue(Advisor.evaluate(l, Advisor.Kind.PURCHASE, "Another Hat", 1e6, NOW).blocked());
		assertFalse(Advisor.evaluate(l, Advisor.Kind.PURCHASE, "Totem of Undying", 1e6, NOW).blocked());
	}

	@Test
	void weekResetsOnMonday() {
		Config c = new Config();
		Data d = new Data();
		Ledger l = ledger(c, d);
		long sunday = NOW - 24L * 3600 * 1000;
		l.record(new Tx(sunday, Tx.Kind.PURCHASE, 400e6, "x", "test", false));
		assertEquals(0, l.spentThisWeek(NOW));
		assertEquals(400e6, l.spentThisMonth(NOW));
	}

	@Test
	void earningsFundGoalsInOrder() {
		Config c = new Config();
		c.savePercent = 50;
		Data d = new Data();
		d.goals.add(new Goal("Sword", 10e6, NOW));
		d.goals.add(new Goal("Base", 100e6, NOW));
		Ledger l = ledger(c, d);
		l.record(new Tx(NOW, Tx.Kind.SALE, 30e6, "Diamonds", "test", false));
		assertEquals(10e6, d.goals.get(0).saved);
		assertEquals(5e6, d.goals.get(1).saved);
		assertTrue(d.goals.get(0).done());
		assertEquals(15e6, l.goalFunds());
	}

	@Test
	void strictModeRejectsLoosening() {
		Config now = new Config();
		Config next = now.copy();
		next.dailyLimit = 200e6;
		assertNotNull(now.loosenedBy(next));
		next = now.copy();
		next.dailyLimit = 50e6;
		assertNull(now.loosenedBy(next));
		next.dailyLimit = 0;
		assertNotNull(now.loosenedBy(next), "0 means unlimited, which is looser");
	}

	// ---------------------------------------------------------------- reconciler

	@Test
	void messageAndBalanceChangeCountOnce() {
		Config c = new Config();
		Data d = new Data();
		Ledger l = ledger(c, d);
		Reconciler r = new Reconciler(l);
		r.balance(1.50e9, 0.01e9, "scoreboard", NOW);
		r.message(Tx.Kind.TRANSFER_OUT, 102e6, "Steve", NOW + 100);
		r.balance(1.40e9, 0.01e9, "scoreboard", NOW + 1500);
		r.tick(NOW + 30_000);
		assertEquals(1, d.history.size());
		assertEquals(102e6, d.history.get(0).amount);
		assertEquals(Tx.Kind.TRANSFER_OUT, d.history.get(0).kind);
	}

	@Test
	void unexplainedDropBecomesOtherAndRoundingIsIgnored() {
		Config c = new Config();
		Data d = new Data();
		Ledger l = ledger(c, d);
		Reconciler r = new Reconciler(l);
		r.balance(1.50e9, 0.01e9, "scoreboard", NOW);
		r.balance(1.49e9, 0.01e9, "scoreboard", NOW + 1000); // one digit: rounding
		r.balance(1.20e9, 0.01e9, "scoreboard", NOW + 2000);
		r.tick(NOW + 20_000);
		assertEquals(1, d.history.size());
		assertEquals(Tx.Kind.OTHER_OUT, d.history.get(0).kind);
		assertEquals(0.29e9, d.history.get(0).amount, 1);
		assertTrue(d.history.get(0).estimated);
	}

	@Test
	void menuPurchaseMatchesBalanceDrop() {
		Config c = new Config();
		Data d = new Data();
		Ledger l = ledger(c, d);
		Reconciler r = new Reconciler(l);
		r.balance(500e6, 0, "chat", NOW);
		r.expect("Elytra", 40e6, Tx.Kind.PURCHASE, NOW + 10);
		r.balance(460e6, 0, "chat", NOW + 1000);
		r.tick(NOW + 60_000);
		assertEquals(1, d.history.size());
		assertEquals(Tx.Kind.PURCHASE, d.history.get(0).kind);
		assertEquals("Elytra", d.history.get(0).what);
	}

	@Test
	void menuPurchaseWithoutDropIsNotCountedWhenBalanceIsLive() {
		Config c = new Config();
		Data d = new Data();
		Ledger l = ledger(c, d);
		Reconciler r = new Reconciler(l);
		r.balance(500e6, 0, "chat", NOW);
		r.expect("Elytra", 40e6, Tx.Kind.PURCHASE, NOW);
		r.balance(500e6, 0, "chat", NOW + 20_000);
		r.tick(NOW + 30_000);
		assertTrue(d.history.isEmpty(), "the server never took the money");
	}

	@Test
	void milestonesFireOnce() {
		Config c = new Config();
		Data d = new Data();
		Ledger l = ledger(c, d);
		int[] fired = {0};
		l.listener(new Ledger.Listener() {
			@Override
			public void milestone(String id, String title, String message) {
				fired[0]++;
			}
		});
		l.balance(600e6, 0, "t", NOW);
		l.balance(700e6, 0, "t", NOW);
		assertEquals(2, fired[0]); // 100M and 500M
		assertTrue(d.achievements.contains("save_500m"));
	}
}
