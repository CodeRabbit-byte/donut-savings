package com.coderabbit.donutsavings.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/** JSON files under {@code config/donutsavings/}. Writes go to a temp file first, then replace. */
public final class Store {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
			.serializeSpecialFloatingPointValues().create();

	private Store() {
	}

	public static <T> T read(Path file, Class<T> type, T fallback) {
		if (!Files.isRegularFile(file)) {
			return fallback;
		}

		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			T t = GSON.fromJson(r, type);
			return t == null ? fallback : t;
		} catch (IOException | JsonParseException e) {
			// Keep the broken file for the user instead of overwriting their history with nothing.
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// nothing more to do
			}

			return fallback;
		}
	}

	public static void write(Path file, Object value) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);

		// Windows Defender or the indexer can hold the target for a moment.
		for (int attempt = 0; ; attempt++) {
			try {
				try {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				}

				return;
			} catch (AccessDeniedException e) {
				if (attempt >= 4) {
					Files.copy(tmp, file, StandardCopyOption.REPLACE_EXISTING);
					Files.deleteIfExists(tmp);
					return;
				}

				try {
					Thread.sleep(25L * (attempt + 1));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}
}
