package com.coderabbit.donutsavings.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Raw server text for tuning the detection patterns: system chat, scoreboard and the menu items you
 * click. Off by default ({@code /savings debug}). Lives in {@code config/donutsavings/debug.log}.
 */
public final class DebugLog {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final long MAX_BYTES = 2_000_000;

	private DebugLog() {
	}

	public static Path file() {
		return Savings.dir().resolve("debug.log");
	}

	public static void write(String kind, String text) {
		try {
			Path f = file();
			Files.createDirectories(f.getParent());

			if (Files.isRegularFile(f) && Files.size(f) > MAX_BYTES) {
				Files.move(f, f.resolveSibling("debug.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}

			Files.writeString(f, LocalDateTime.now().format(TIME) + " [" + kind + "] " + text + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			DonutSavingsClient.LOGGER.debug("debug log write failed", e);
		}
	}
}
