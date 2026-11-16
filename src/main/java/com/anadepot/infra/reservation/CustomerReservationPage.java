package com.anadepot.infra.reservation;

import java.util.List;

/** One page of a customer's own reservations (newest first). */
public record CustomerReservationPage(List<CustomerReservationView> items, int page, int size, long totalItems, int totalPages) {
}
