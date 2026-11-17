package com.anadepot.core.customer.model;

import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.Map;

/**
 * What a customer's reservations add up to. Money is ALWAYS per currency (HTG and USD are never summed or converted):
 * {@code collected} = the totals of reservations that were collected (what the customer actually bought);
 * {@code openDue} = what is still to be paid on reservations that are still open.
 */
public record CustomerSummary(Map<ReservationStatus, Long> reservationsByStatus, Map<SupportedCurrency, Money> collected, Map<SupportedCurrency, Money> openDue) {
}
