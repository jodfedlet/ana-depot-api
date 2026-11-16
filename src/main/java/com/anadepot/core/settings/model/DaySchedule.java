package com.anadepot.core.settings.model;

/** One weekday of opening hours. {@code day}: 1 = Monday ... 7 = Sunday; {@code from}/{@code to} are "HH:MM" (24h). */
public record DaySchedule(int day, boolean open, String from, String to) {
}
