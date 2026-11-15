package com.anadepot.core.reservation.model;

import com.anadepot.core.shared.money.Money;

/** What is owed, paid and still due in ONE currency. Balances of different currencies are never combined. */
public record Balance(Money total, Money paid, Money due) {

	public boolean settled() {
		return due.isZero();
	}
}
