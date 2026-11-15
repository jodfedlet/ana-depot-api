package com.anadepot.core.reservation.model;

import com.anadepot.core.shared.Actor;
import java.time.Instant;

/** Append-only history entry. {@code from} is null for the creation entry. */
public record StatusChange(ReservationStatus from, ReservationStatus to, String reasonCode, String reasonNote, Instant at, Actor actor) {
}
