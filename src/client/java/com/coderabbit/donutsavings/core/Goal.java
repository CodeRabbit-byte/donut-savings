package com.coderabbit.donutsavings.core;

/**
 * A named savings goal. {@code saved} is money set aside for it: a share of every earning (the
 * savings percentage) plus anything added by hand. Money set aside counts as protected, so
 * spending into it is treated like going below the minimum balance.
 */
public final class Goal {
	public String name = "Goal";
	public double target;
	public double saved;
	public long created;
	public long completedAt;

	public Goal() {
	}

	public Goal(String name, double target, long now) {
		this.name = name;
		this.target = target;
		this.created = now;
	}

	public boolean done() {
		return target > 0 && saved >= target;
	}

	public double remaining() {
		return Math.max(0, target - saved);
	}

	public double fraction() {
		return target <= 0 ? 0 : Math.min(1, saved / target);
	}
}
