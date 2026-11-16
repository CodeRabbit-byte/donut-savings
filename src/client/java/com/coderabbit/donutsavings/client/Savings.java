package com.coderabbit.donutsavings.client;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;

import com.coderabbit.donutsavings.core.Config;
import com.coderabbit.donutsavings.core.Data;
import com.coderabbit.donutsavings.core.Ledger;
import com.coderabbit.donutsavings.core.Reconciler;
import com.coderabbit.donutsavings.core.Store;

import net.fabricmc.loader.api.FabricLoader;

/** The mod's state and its files. Everything runs on the client thread. */
public final class Savings {
	private static Path dir;
	private static Config config = new Config();
	private static Data data = new Data();
	private static Ledger ledger;
	private static Reconciler reconciler;
	private static boolean dirty;
	private static long lastSave;

	private Savings() {
	}

	public static void load() {
		dir = FabricLoader.getInstance().getConfigDir().resolve("donutsavings");
		config = Store.read(dir.resolve("config.json"), Config.class, new Config());
		config.repair();
		data = Store.read(dir.resolve("data.json"), Data.class, new Data());
		data.repair();
		ledger = new Ledger(data, Savings::config, ZoneId.systemDefault());
		ledger.listener(new Notify.LedgerEvents());
		reconciler = new Reconciler(ledger);
		saveConfig();
	}

	/** Re-reads config.json (after a hand edit). Data stays as it is in memory. */
	public static void reloadConfig() {
		Config c = Store.read(dir.resolve("config.json"), Config.class, null);

		if (c != null) {
			c.repair();
			config = c;
		}
	}

	public static Path dir() {
		return dir;
	}

	public static Config config() {
		return config;
	}

	public static void setConfig(Config c) {
		c.repair();
		config = c;
		saveConfig();
	}

	public static Data data() {
		return data;
	}

	public static Ledger ledger() {
		return ledger;
	}

	public static Reconciler reconciler() {
		return reconciler;
	}

	public static void markDirty() {
		dirty = true;
	}

	/** Saves the data at most every 10 seconds unless forced. */
	public static void saveData(boolean force) {
		long now = System.currentTimeMillis();

		if (!force && (!dirty || now - lastSave < 10_000)) {
			return;
		}

		try {
			Store.write(dir.resolve("data.json"), data);
			dirty = false;
			lastSave = now;
		} catch (IOException e) {
			DonutSavingsClient.LOGGER.warn("Could not save donutsavings data", e);
		}
	}

	public static void saveConfig() {
		try {
			Store.write(dir.resolve("config.json"), config);
		} catch (IOException e) {
			DonutSavingsClient.LOGGER.warn("Could not save donutsavings config", e);
		}
	}
}
