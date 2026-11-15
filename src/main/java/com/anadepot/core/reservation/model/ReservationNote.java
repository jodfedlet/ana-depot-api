package com.anadepot.core.reservation.model;

import com.anadepot.core.shared.Actor;
import java.time.Instant;
import java.util.UUID;

public record ReservationNote(UUID id, NoteVisibility visibility, String body, Instant at, Actor author) {
}
