package com.coderabbit.donutsavings.core;

import java.util.List;

/** Savings milestones. Reached when the balance first gets to the amount. */
public final class Achievements {
	public record Milestone(String id, String title, double amount, String message) {
	}

	public static final String MAIN_GOAL = "main_goal";

	public static final List<Milestone> MILESTONES = List.of(
			new Milestone("save_100m", "Saved 100M", 100e6, "100M saved. The first step is the hardest - keep going!"),
			new Milestone("save_500m", "Saved 500M", 500e6, "Half a billion. Every purchase you skipped got you here."),
			new Milestone("save_1b", "Saved 1B", 1e9, "ONE BILLION. You are officially a saver, not a spender."),
			new Milestone("save_5b", "Saved 5B", 5e9, "5B! Discipline pays. Literally."));

	private Achievements() {
	}

	public static String mainGoalTitle(double goal) {
		return "Reached your goal (" + Money.format(goal) + ")";
	}
}
