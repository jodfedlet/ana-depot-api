package com.anadepot.core.shared;

import java.util.UUID;

/** The resource the command produced; {@code replayed} is true when this is the stored result of an earlier identical request. */
public record IdempotentOutcome(UUID resourceId, boolean replayed) {
}
