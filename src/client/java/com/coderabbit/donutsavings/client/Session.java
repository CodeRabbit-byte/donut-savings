package com.coderabbit.donutsavings.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.coderabbit.donutsavings.core.ChatParser;
import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Money;
import com.coderabbit.donutsavings.core.Text;
import com.coderabbit.donutsavings.core.Tx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/** Where we are, and the balance the server shows. */
public final class Session {
	/** Ignore the scoreboard this long after joining: it can briefly show 0 while loading. */
	private static final long JOIN_GRACE = 5_000;

	private static long joinedAt;
	private static int ticks;
	private static int playTicks;
	/** A scoreboard reading is only trusted once it shows the same value twice in a row. */
	private static double candidate = -1;
	private static String lastSidebarDump = "";

	private Session() {
	}

	public static void joined() {
		joinedAt = System.currentTimeMillis();
		candidate = -1;
		Savings.reconciler().reset();
	}

	public static void left() {
		Savings.reconciler().reset();
		candidate = -1;
		Savings.saveData(true);
	}

	public static String serverAddress() {
		ServerData s = Minecraft.getInstance().getCurrentServer();
		return s == null ? "" : s.ip;
	}

	/** True while connected to a server the mod protects. */
	public static boolean active() {
		Minecraft mc = Minecraft.getInstance();

		if (mc.getConnection() == null || mc.player == null) {
			return false;
		}

		Config c = Savings.config();

		if (!c.onlyOnDonut) {
			return true;
		}

		String filter = c.serverFilter == null ? "" : c.serverFilter.trim().toLowerCase(Locale.ROOT);
		return !filter.isEmpty() && serverAddress().toLowerCase(Locale.ROOT).contains(filter);
	}

	public static void tick(Minecraft mc) {
		ticks++;
		long now = System.currentTimeMillis();

		if (!active()) {
			if (ticks % 200 == 0) {
				Savings.saveData(false);
			}

			return;
		}

		if (++playTicks >= 20 * 60) {
			playTicks = 0;
			Savings.ledger().addPlayTime(60, now);
			Savings.markDirty();
		}

		if (ticks % 20 == 0) {
			readScoreboard(mc, now);
			Savings.reconciler().tick(now);
			Savings.saveData(false);
		}
	}

	/** The sidebar lines as the player sees them: name with team prefix/suffix, then the score. */
	public static List<String> sidebarLines(Minecraft mc) {
		List<String> out = new ArrayList<>();

		if (mc.level == null) {
			return out;
		}

		Scoreboard sb = mc.level.getScoreboard();
		Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);

		if (obj == null) {
			return out;
		}

		out.add(Text.normalize(obj.getDisplayName().getString()));

		for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
			if (e.isHidden()) {
				continue;
			}

			Component name = PlayerTeam.formatNameForTeam(sb.getPlayersTeam(e.owner()), e.ownerName());
			String score = e.formatValue(obj.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)).getString();
			out.add(Text.normalize(name.getString() + " " + score));
		}

		return out;
	}

	private static void readScoreboard(Minecraft mc, long now) {
		if (now - joinedAt < JOIN_GRACE) {
			return;
		}

		List<String> lines = sidebarLines(mc);
		Config c = Savings.config();

		if (c.debugLog) {
			String dump = String.join(" | ", lines);

			if (!dump.equals(lastSidebarDump)) {
				lastSidebarDump = dump;
				DebugLog.write("sidebar", dump);
			}
		}

		for (String line : lines) {
			Money.Found f = ChatParser.scoreboardBalance(c, line);

			if (f == null) {
				continue;
			}

			if (f.value() == candidate) {
				if (f.value() != Savings.data().balance) {
					Savings.markDirty();
				}

				// Same value again also refreshes "balance is live" for the reconciler.
				Savings.reconciler().balance(f.value(), f.step(), "scoreboard", now);
			}

			candidate = f.value();
			return;
		}
	}

	/** A system chat line from the server. */
	public static void onServerMessage(Component message) {
		if (!active()) {
			return;
		}

		String line = Text.normalize(message.getString());
		Config c = Savings.config();

		if (c.debugLog) {
			DebugLog.write("chat", line);
		}

		ChatParser.Result r = ChatParser.parse(c, line);

		if (r == null) {
			return;
		}

		long now = System.currentTimeMillis();

		switch (r.type()) {
			case BALANCE -> Savings.reconciler().balance(r.amount(), r.step(), "chat", now);
			case PAY_SENT -> Savings.reconciler().message(Tx.Kind.TRANSFER_OUT, r.amount(), r.who(), now);
			case PAY_RECEIVED -> Savings.reconciler().message(Tx.Kind.TRANSFER_IN, r.amount(), r.who(), now);
			case BOUGHT -> Savings.reconciler().message(Tx.Kind.PURCHASE, r.amount(), r.who(), now);
			case SOLD -> Savings.reconciler().message(Tx.Kind.SALE, r.amount(), r.who(), now);
		}

		Savings.markDirty();
	}
}
