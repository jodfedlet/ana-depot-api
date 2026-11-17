package com.anadepot.core.report.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** How a time series is bucketed. A bucket is named by its first day: the day itself, the Monday of the week, or the first of the month. */
public enum Grouping {
	DAY, WEEK, MONTH;

	public LocalDate bucketOf(LocalDate day) {
		return switch (this) {
			case DAY -> day;
			case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			case MONTH -> day.withDayOfMonth(1);
		};
	}

	public LocalDate next(LocalDate bucket) {
		return switch (this) {
			case DAY -> bucket.plusDays(1);
			case WEEK -> bucket.plusWeeks(1);
			case MONTH -> bucket.plusMonths(1);
		};
	}
}
