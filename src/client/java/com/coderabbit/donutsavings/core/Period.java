package com.coderabbit.donutsavings.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Budget windows in local time. Limits "reset" because spending is always summed from the start of
 * the current day, week or month: nothing has to be cleared when the window rolls over.
 */
public final class Period {
	private Period() {
	}

	private static LocalDate date(long now, ZoneId zone) {
		return Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
	}

	private static long millis(LocalDate d, ZoneId zone) {
		return d.atStartOfDay(zone).toInstant().toEpochMilli();
	}

	public static long startOfDay(long now, ZoneId zone) {
		return millis(date(now, zone), zone);
	}

	public static long startOfWeek(long now, ZoneId zone, DayOfWeek first) {
		return millis(date(now, zone).with(TemporalAdjusters.previousOrSame(first)), zone);
	}

	public static long startOfMonth(long now, ZoneId zone) {
		return millis(date(now, zone).withDayOfMonth(1), zone);
	}

	public static long startOfNextDay(long now, ZoneId zone) {
		return millis(date(now, zone).plusDays(1), zone);
	}

	public static long startOfNextWeek(long now, ZoneId zone, DayOfWeek first) {
		return millis(date(now, zone).with(TemporalAdjusters.next(first)), zone);
	}

	public static long startOfNextMonth(long now, ZoneId zone) {
		return millis(date(now, zone).withDayOfMonth(1).plusMonths(1), zone);
	}

	/** "2026-11-16": the key the per-day play time is stored under. */
	public static String dayKey(long now, ZoneId zone) {
		return date(now, zone).toString();
	}

	/** "3h 05m", "12m", "45s". */
	public static String duration(long millis) {
		long s = Math.max(0, millis / 1000);

		if (s < 60) {
			return s + "s";
		}

		long m = s / 60;

		if (m < 60) {
			return m + "m " + String.format("%02ds", s % 60);
		}

		long h = m / 60;

		if (h < 48) {
			return h + "h " + String.format("%02dm", m % 60);
		}

		return (h / 24) + "d " + (h % 24) + "h";
	}
}
