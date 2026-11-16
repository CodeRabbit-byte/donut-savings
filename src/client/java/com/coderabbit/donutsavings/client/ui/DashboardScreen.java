package com.coderabbit.donutsavings.client.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.coderabbit.donutsavings.client.Notify;
import com.coderabbit.donutsavings.client.Savings;
import com.coderabbit.donutsavings.client.Session;
import com.coderabbit.donutsavings.core.Achievements;
import com.coderabbit.donutsavings.core.Advisor;
import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Data;
import com.coderabbit.donutsavings.core.Goal;
import com.coderabbit.donutsavings.core.Ledger;
import com.coderabbit.donutsavings.core.Money;
import com.coderabbit.donutsavings.core.Period;
import com.coderabbit.donutsavings.core.Tx;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The money dashboard: overview, goals, history, achievements, and what the mod can protect. */
public class DashboardScreen extends Screen {
	private enum Tab {
		OVERVIEW("Overview"), GOALS("Goals"), HISTORY("History"), ACHIEVEMENTS("Achievements"), PROTECTION("Protection");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private enum Filter {
		ALL("All"), SPENDING("Spending"), EARNINGS("Earnings"), PURCHASES("Purchases"), SALES("Sales"),
		TRANSFERS("Transfers"), OTHER("Other"), TOP("Most expensive");

		final String label;

		Filter(String label) {
			this.label = label;
		}

		boolean accepts(Tx t) {
			return switch (this) {
				case ALL, TOP -> true;
				case SPENDING -> t.kind.out;
				case EARNINGS -> !t.kind.out;
				case PURCHASES -> t.kind == Tx.Kind.PURCHASE;
				case SALES -> t.kind == Tx.Kind.SALE;
				case TRANSFERS -> t.kind == Tx.Kind.TRANSFER_IN || t.kind == Tx.Kind.TRANSFER_OUT;
				case OTHER -> t.kind == Tx.Kind.OTHER_IN || t.kind == Tx.Kind.OTHER_OUT;
			};
		}
	}

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM-dd HH:mm");
	private static Tab lastTab = Tab.OVERVIEW;

	private final Screen parent;
	private Tab tab = lastTab;
	private Filter filter = Filter.ALL;
	private int scroll;
	private int x0;
	private int w;
	private int contentTop;
	private EditBox goalName;
	private EditBox goalTarget;
	private EditBox fundsBox;
	private String message = "";
	private int messageColor = Ui.DIM;

	public DashboardScreen(Screen parent) {
		super(Component.literal("Savings Dashboard"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		w = Math.min(width - 12, 460);
		x0 = (width - w) / 2;
		int tabs = Tab.values().length;
		int tw = (w - (tabs - 1) * 2) / tabs;

		for (int i = 0; i < tabs; i++) {
			Tab t = Tab.values()[i];
			Button b = addRenderableWidget(Button.builder(Component.literal(t.label), btn -> {
				tab = t;
				lastTab = t;
				scroll = 0;
				message = "";
				rebuildWidgets();
			}).bounds(x0 + i * (tw + 2), 22, tw, 18).build());
			b.active = t != tab;
		}

		contentTop = 46;

		switch (tab) {
			case GOALS -> initGoals();
			case HISTORY -> addRenderableWidget(Button.builder(Component.literal("Show: " + filter.label), b -> {
				filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
				scroll = 0;
				rebuildWidgets();
			}).bounds(x0 + w - 130, contentTop, 130, 18).build());
			default -> {
			}
		}

		int by = height - 24;
		addRenderableWidget(Button.builder(Component.literal("Settings"), b -> minecraft.setScreen(new SettingsScreen(this)))
				.bounds(width / 2 - 104, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(width / 2 + 4, by, 100, 20).build());
	}

	// ---------------------------------------------------------------- goals tab

	private void initGoals() {
		int y = height - 50;
		int nameW = Math.max(60, w - 90 - 70 - 8);
		goalName = new EditBox(font, x0, y, nameW, 18, Component.literal("Goal name"));
		goalName.setMaxLength(32);
		goalName.setHint(Component.literal("New goal name").withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(goalName);
		goalTarget = new EditBox(font, x0 + nameW + 4, y, 86, 18, Component.literal("Target"));
		goalTarget.setMaxLength(16);
		goalTarget.setHint(Component.literal("e.g. 1b").withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(goalTarget);
		addRenderableWidget(Button.builder(Component.literal("Add goal"), b -> addGoal())
				.bounds(x0 + w - 66, y, 66, 18).build());

		fundsBox = new EditBox(font, x0 + w - 150, contentTop, 80, 16, Component.literal("Amount"));
		fundsBox.setMaxLength(16);
		fundsBox.setHint(Component.literal("amount").withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(fundsBox);

		List<Goal> goals = Savings.data().goals;
		int rowH = 30;
		int listTop = contentTop + 22;
		int rows = Math.max(1, (height - 56 - listTop) / rowH);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, goals.size() - rows)));

		for (int i = scroll; i < goals.size() && i < scroll + rows; i++) {
			Goal g = goals.get(i);
			int ry = listTop + (i - scroll) * rowH;
			addRenderableWidget(Button.builder(Component.literal("+"), b -> fund(g)).bounds(x0 + w - 44, ry + 2, 20, 18).build());
			addRenderableWidget(Button.builder(Component.literal("x").withStyle(ChatFormatting.RED), b -> deleteGoal(g))
					.bounds(x0 + w - 22, ry + 2, 20, 18).build());
		}
	}

	private void addGoal() {
		String name = goalName.getValue().trim();
		double target = Money.parse(goalTarget.getValue());

		if (name.isEmpty() || target <= 0) {
			say("Give the goal a name and a target like 500m or 2b.", Ui.RED);
			return;
		}

		Savings.data().goals.add(new Goal(name, target, System.currentTimeMillis()));
		Savings.markDirty();
		Savings.saveData(true);
		say("Goal added: " + name + " (" + Money.format(target) + ").", Ui.GREEN);
		rebuildWidgets();
	}

	private void fund(Goal g) {
		double amount = Money.parse(fundsBox.getValue());

		if (amount <= 0) {
			say("Type an amount in the box at the top right first (e.g. 50m), then press +.", Ui.YELLOW);
			return;
		}

		double take = Math.min(amount, g.remaining());
		g.saved += take;

		if (g.done() && g.completedAt == 0) {
			g.completedAt = System.currentTimeMillis();
			Notify.good("GOAL COMPLETE: " + g.name + "!");
			Notify.celebrate();
		}

		Savings.markDirty();
		say("Set aside " + Money.format(take) + " for " + g.name + ".", Ui.GREEN);
	}

	private void deleteGoal(Goal g) {
		if (Savings.config().strictMode && g.saved > 0 && !g.done()) {
			say("Strict Savings Mode: can't delete a goal that still holds " + Money.format(g.saved) + ".", Ui.RED);
			Notify.denied();
			return;
		}

		Savings.data().goals.remove(g);
		Savings.markDirty();
		say("Removed " + g.name + ".", Ui.DIM);
		rebuildWidgets();
	}

	private void say(String s, int color) {
		message = s;
		messageColor = color;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (vertical != 0 && (tab == Tab.HISTORY || tab == Tab.GOALS)) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical) * (tab == Tab.HISTORY ? 3 : 1));

			if (tab == Tab.GOALS) {
				rebuildWidgets();
			}

			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public void onClose() {
		Savings.saveData(true);
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, Ui.BG);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		Config c = Savings.config();
		String title = "Savings Dashboard";
		g.drawCenteredString(font, Component.literal(title).withStyle(ChatFormatting.BOLD), width / 2, 7, Ui.GOLD);

		if (c.strictMode) {
			String badge = "STRICT";
			int bw = font.width(badge) + 8;
			g.fill(x0 + w - bw, 5, x0 + w, 17, 0xFF1E6B3A);
			g.drawString(font, badge, x0 + w - bw + 4, 7, Ui.TEXT, false);
		}

		if (!Session.active()) {
			g.drawString(font, c.onlyOnDonut ? "Not on DonutSMP: tracking paused" : "Not connected", x0, 7, Ui.DIM, false);
		}

		long now = System.currentTimeMillis();

		switch (tab) {
			case OVERVIEW -> drawOverview(g, now);
			case GOALS -> drawGoals(g);
			case HISTORY -> drawHistory(g, now);
			case ACHIEVEMENTS -> drawAchievements(g);
			case PROTECTION -> drawProtection(g);
		}

		if (!message.isEmpty()) {
			g.drawCenteredString(font, Ui.clip(font, message, width - 10), width / 2, height - 36, messageColor);
		}

		super.render(g, mouseX, mouseY, delta);
	}

	private void drawOverview(GuiGraphics g, long now) {
		Ledger l = Savings.ledger();
		Data d = Savings.data();
		Config c = Savings.config();
		int colW = (w - 10) / 2;
		int lx = x0;
		int lr = x0 + colW;
		int rx = lr + 10;
		int rr = x0 + w;
		int y = contentTop;

		Ui.panel(g, lx, y, colW, 100);
		Ui.panel(g, rx, y, colW, 100);
		int ly = y + 6;
		Ui.text(g, font, "Money", lx + 6, ly, Ui.GOLD);
		ly += 12;
		String bal = l.hasBalance() ? Money.format(d.balance) : "unknown";
		Ui.row(g, font, "Current balance", bal, lx + 6, lr - 6, ly, Ui.TEXT);
		ly += 10;
		String src = l.hasBalance() ? d.balanceSource + ", " + Period.duration(now - d.balanceAt) + " ago" : "open /bal or check scoreboard";
		Ui.text(g, font, Ui.clip(font, src, colW - 12), lx + 6, ly, Ui.DIM);
		ly += 12;
		Ui.row(g, font, "Total earned", Money.format(d.totalEarned), lx + 6, lr - 6, ly, Ui.GREEN);
		ly += 11;
		Ui.row(g, font, "Total spent", Money.format(d.totalSpent), lx + 6, lr - 6, ly, Ui.RED);
		ly += 11;
		double net = l.netSaved();
		Ui.row(g, font, "Money saved (net)", Money.formatSigned(net), lx + 6, lr - 6, ly, net >= 0 ? Ui.GREEN : Ui.RED);
		ly += 11;
		Ui.row(g, font, "Kept by walking away", Money.format(d.walkedAwayAmount), lx + 6, lr - 6, ly, Ui.BLUE);
		ly += 11;
		Ui.row(g, font, "Payments blocked", String.valueOf(d.paymentsBlocked), lx + 6, lr - 6, ly, Ui.BLUE);

		int ry = y + 6;
		Ui.text(g, font, "Spending", rx + 6, ry, Ui.GOLD);
		ry += 12;
		ry = allowance(g, "Today", l.spentToday(now), c.dailyLimit, rx + 6, rr - 6, ry);
		ry = allowance(g, "This week", l.spentThisWeek(now), c.weeklyLimit, rx + 6, rr - 6, ry);
		ry = allowance(g, "This month", l.spentThisMonth(now), c.monthlyLimit, rx + 6, rr - 6, ry);
		double left = l.remainingAllowance(now);
		Ui.row(g, font, "Allowance left", Money.format(left), rx + 6, rr - 6, ry, left <= 0 ? Ui.RED : Ui.GREEN);
		ry += 11;
		double rate = l.earningsPerHour(now);
		Ui.row(g, font, "Earning rate", Double.isNaN(rate) ? "not enough data" : Money.format(rate) + "/h", rx + 6, rr - 6, ry,
				Double.isNaN(rate) ? Ui.DIM : Ui.TEXT);

		// savings goal
		y += 106;
		Ui.panel(g, x0, y, w, 48);
		double goal = c.mainGoal;
		Ui.text(g, font, "Savings goal", x0 + 6, y + 6, Ui.GOLD);

		if (goal > 0 && l.hasBalance()) {
			double frac = d.balance / goal;
			String right = Money.format(d.balance) + " / " + Money.format(goal) + String.format("  (%.1f%%)", Math.min(100, frac * 100));
			g.drawString(font, right, x0 + w - 6 - font.width(right), y + 6, Ui.TEXT, false);
			Ui.bar(g, x0 + 6, y + 18, w - 12, 10, frac, frac >= 1 ? Ui.GOLD : Ui.GREEN);
			String need = frac >= 1 ? "Goal reached! Set a bigger one in Settings." : "Need " + Money.format(goal - d.balance) + " more";
			String eta = "";

			if (frac < 1 && !Double.isNaN(rate) && rate > 0) {
				eta = "  ~" + Advisor.earnBack((goal - d.balance) / rate) + " at your rate";
			}

			Ui.text(g, font, Ui.clip(font, need + eta, w - 12), x0 + 6, y + 33, frac >= 1 ? Ui.GOLD : Ui.DIM);
		} else {
			Ui.text(g, font, goal > 0 ? "Balance unknown yet." : "No goal set (Settings).", x0 + 6, y + 20, Ui.DIM);
		}

		// protection line
		y += 54;
		double floor = l.floor();
		String prot = "/pay " + (c.blocksPay() ? "BLOCKED" : "confirm") + "  |  menu purchases: " + (c.spendingLock ? "LOCKED" : c.confirms() ? "confirm" : "warn")
				+ "  |  floor " + Money.format(floor) + (c.protectGoalFunds && l.goalFunds() > 0 ? " (incl. goal funds)" : "");
		Ui.text(g, font, Ui.clip(font, prot, w), x0, y, c.strictMode ? Ui.GREEN : Ui.DIM);

		if (l.hasBalance() && floor > 0 && d.balance < floor) {
			Ui.text(g, font, "You are BELOW your floor. Do not spend anything.", x0, y + 11, Ui.RED);
		}
	}

	private int allowance(GuiGraphics g, String label, double spent, double limit, int x, int right, int y) {
		String value = limit > 0 ? Money.format(spent) + " / " + Money.format(limit) : Money.format(spent) + " (no limit)";
		double used = limit > 0 ? spent / limit : 0;
		Ui.row(g, font, label, value, x, right, y, limit > 0 ? Ui.usageColor(used) : Ui.TEXT);

		if (limit > 0) {
			Ui.bar(g, x, y + 9, right - x, 4, used, Ui.usageColor(used));
		}

		return y + 15;
	}

	private void drawGoals(GuiGraphics g) {
		Data d = Savings.data();
		Config c = Savings.config();
		Ledger l = Savings.ledger();
		String head = String.format("%.0f%% of every earning is set aside", c.savePercent) + "  |  set aside: " + Money.format(l.goalFunds());
		Ui.text(g, font, Ui.clip(font, head, w - 160), x0, contentTop + 4, Ui.DIM);

		if (d.goals.isEmpty()) {
			Ui.text(g, font, "No goals yet. Add one below - e.g. \"New sword\" 500m, \"Base\" 2b.", x0, contentTop + 30, Ui.DIM);
		}

		int rowH = 30;
		int listTop = contentTop + 22;
		int rows = Math.max(1, (height - 56 - listTop) / rowH);

		for (int i = scroll; i < d.goals.size() && i < scroll + rows; i++) {
			Goal goal = d.goals.get(i);
			int ry = listTop + (i - scroll) * rowH;
			int bw = w - 50;
			Ui.panel(g, x0, ry, bw, rowH - 4);
			String right = Money.format(goal.saved) + " / " + Money.format(goal.target) + String.format(" (%.0f%%)", goal.fraction() * 100);
			Ui.text(g, font, Ui.clip(font, (i + 1) + ". " + goal.name, bw - 12 - font.width(right) - 6), x0 + 6, ry + 4,
					goal.done() ? Ui.GOLD : Ui.TEXT);
			g.drawString(font, right, x0 + bw - 6 - font.width(right), ry + 4, goal.done() ? Ui.GOLD : Ui.TEXT, false);
			Ui.bar(g, x0 + 6, ry + 15, bw - 12, 6, goal.fraction(), goal.done() ? Ui.GOLD : Ui.GREEN);
		}

		if (l.hasBalance() && l.goalFunds() > d.balance) {
			Ui.text(g, font, "Warning: you have less money than you set aside. You spent your goal funds!", x0, height - 62, Ui.RED);
		}
	}

	private void drawHistory(GuiGraphics g, long now) {
		Ledger l = Savings.ledger();
		Ui.text(g, font, Ui.clip(font, "Purchases, sales and transfers kept apart. ~ = estimated", w - 136), x0, contentTop + 5, Ui.DIM);
		int y = contentTop + 23;
		y = summary(g, "Today", l.dayStart(now), y);
		y = summary(g, "Week", l.weekStart(now), y);
		y = summary(g, "Month", l.monthStart(now), y);
		y += 4;

		List<Tx> list = new ArrayList<>();

		if (filter == Filter.TOP) {
			list = l.topPurchases(200);
		} else {
			List<Tx> h = Savings.data().history;

			for (int i = h.size() - 1; i >= 0; i--) {
				if (filter.accepts(h.get(i))) {
					list.add(h.get(i));
				}
			}
		}

		int rows = Math.max(1, (height - 40 - y) / 10);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - rows)));

		if (list.isEmpty()) {
			Ui.text(g, font, "Nothing recorded yet.", x0, y, Ui.DIM);
			return;
		}

		ZoneId zone = Savings.ledger().zone();
		int amountRight = x0 + 170;

		for (int i = scroll; i < list.size() && i < scroll + rows; i++) {
			Tx t = list.get(i);
			String when = WHEN.format(Instant.ofEpochMilli(t.time).atZone(zone));
			String amount = (t.kind.out ? "-" : "+") + Money.format(t.amount) + (t.estimated ? "~" : "");
			g.drawString(font, when, x0, y, Ui.DIM, false);
			g.drawString(font, amount, amountRight - font.width(amount), y, t.kind.out ? Ui.RED : Ui.GREEN, false);
			String what = t.kind.label + (t.what.isBlank() || "Balance change".equals(t.what) ? "" : ": " + t.what);
			g.drawString(font, Ui.clip(font, what, x0 + w - amountRight - 8), amountRight + 8, y, Ui.TEXT, false);
			y += 10;
		}

		if (list.size() > rows) {
			String s = (scroll + 1) + "-" + Math.min(list.size(), scroll + rows) + " of " + list.size() + " (scroll)";
			g.drawString(font, s, x0 + w - font.width(s), height - 36, Ui.DIM, false);
		}
	}

	private int summary(GuiGraphics g, String label, long from, int y) {
		Ledger l = Savings.ledger();
		double bought = l.sumSince(from, Tx.Kind.PURCHASE);
		double sold = l.sumSince(from, Tx.Kind.SALE);
		double payOut = l.sumSince(from, Tx.Kind.TRANSFER_OUT);
		double payIn = l.sumSince(from, Tx.Kind.TRANSFER_IN);
		double other = l.sumSince(from, Tx.Kind.OTHER_IN) - l.sumSince(from, Tx.Kind.OTHER_OUT);
		double net = l.earnedSince(from) - (bought + payOut + l.sumSince(from, Tx.Kind.OTHER_OUT));
		String s = label + ": bought " + Money.format(bought) + ", sold " + Money.format(sold) + ", paid " + Money.format(payOut)
				+ ", received " + Money.format(payIn) + ", other " + Money.formatSigned(other);
		String n = "net " + Money.formatSigned(net);
		int nw = font.width(n);
		Ui.text(g, font, Ui.clip(font, s, w - nw - 8), x0, y, Ui.TEXT);
		g.drawString(font, n, x0 + w - nw, y, net >= 0 ? Ui.GREEN : Ui.RED, false);
		return y + 10;
	}

	private void drawAchievements(GuiGraphics g) {
		Data d = Savings.data();
		Config c = Savings.config();
		int y = contentTop + 4;
		double bal = Math.max(d.balance, 0);

		for (Achievements.Milestone m : Achievements.MILESTONES) {
			y = achievement(g, m.title(), d.achievements.contains(m.id()), bal / m.amount(), y);
		}

		if (c.mainGoal > 0) {
			y = achievement(g, Achievements.mainGoalTitle(c.mainGoal), d.achievements.contains(Achievements.MAIN_GOAL + ":" + (long) c.mainGoal),
					bal / c.mainGoal, y);
		}

		int done = 0;

		for (Goal goal : d.goals) {
			if (goal.done()) {
				done++;
			}
		}

		y += 6;
		Ui.text(g, font, "Goals completed: " + done + " of " + d.goals.size(), x0, y, Ui.TEXT);
		y += 11;
		Ui.text(g, font, "Purchases you walked away from: " + d.walkedAway + " (" + Money.format(d.walkedAwayAmount) + " kept)", x0, y, Ui.TEXT);
		y += 11;
		Ui.text(g, font, "Highest balance: " + Money.format(d.peakBalance), x0, y, Ui.TEXT);
	}

	private int achievement(GuiGraphics g, String title, boolean got, double progress, int y) {
		Ui.panel(g, x0, y, w, 24);
		g.drawString(font, (got ? "[x] " : "[ ] ") + title, x0 + 6, y + 4, got ? Ui.GOLD : Ui.TEXT, false);
		Ui.bar(g, x0 + 6, y + 15, w - 12, 4, got ? 1 : progress, got ? Ui.GOLD : Ui.GREEN);
		return y + 27;
	}

	private void drawProtection(GuiGraphics g) {
		Config c = Savings.config();
		List<Component> lines = new ArrayList<>();
		lines.add(h("CAN BE BLOCKED (stopped before anything is sent to the server)"));
		lines.add(p("/pay - every form (/pay, /" + "essentials:pay, typed or clicked in chat) is caught before it is sent. Status: "
				+ (c.blocksPay() ? "BLOCKED" : "needs confirming twice")));
		lines.add(p("Menu purchases - a click on a Confirm/Buy button in a server menu (Auction House, shop) is held back until you confirm on the "
				+ "Purchase Check screen. Blocked outright when a limit, the floor, the cooldown or the spending lock says no."));
		lines.add(h("CAN ONLY BE WARNED ABOUT OR TRACKED"));
		lines.add(p("Prices - read from the item lore. If the server does not print one, the limits are checked with an unknown price "
				+ "(the screen says so)."));
		lines.add(p("Buttons named differently (not confirm/buy/purchase) are not recognised. Add their words to buyButton in the config."));
		lines.add(p("Anything the server takes without a menu click or command (taxes, fees, other plugins) can only be tracked afterwards from "
				+ "balance changes: it shows up as Other expense."));
		lines.add(h("HOW TRACKING WORKS"));
		lines.add(p("Exact amounts come from server chat (\"You paid NAME $X\", \"NAME paid you $X\" - confirmed on DonutSMP; shop/auction "
				+ "lines are best guesses). Balance changes come from the scoreboard and /bal, and fill the gaps. Amounts marked ~ are "
				+ "estimated from an abbreviated balance."));
		lines.add(p("Tracking is only active on servers whose address contains \"" + c.serverFilter + "\". Turn on /savings debug to log raw "
				+ "server text if something is missed, then tune the patterns in config/donutsavings/config.json."));
		lines.add(p("The mod never sends commands, clicks or payments by itself."));

		int y = contentTop + 2;

		for (Component line : lines) {
			for (FormattedCharSequence s : font.split(line, w)) {
				if (y > height - 40) {
					return;
				}

				g.drawString(font, s, x0, y, Ui.TEXT, false);
				y += 10;
			}

			y += 2;
		}
	}

	private static Component h(String s) {
		return Component.literal(s).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	}

	private static Component p(String s) {
		return Component.literal(s).withStyle(ChatFormatting.GRAY);
	}
}
