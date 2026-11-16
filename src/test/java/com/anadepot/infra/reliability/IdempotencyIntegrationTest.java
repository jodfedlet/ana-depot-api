package com.anadepot.infra.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.reservation.service.SubmitReservationCommand;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.IdempotencyRequest;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.infra.persistence.JpaIdempotencyService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ana.idempotency.retention=PT0S")   // keys expire immediately so cleanup can be tested
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

	@Autowired IdempotencyService idempotency;
	@Autowired JpaIdempotencyService jpaIdempotency;

	private SubmitReservationCommand command(com.anadepot.core.catalog.model.Product product, int qty, String key, Actor actor) {
		return new SubmitReservationCommand(List.of(line(product, qty, SupportedCurrency.HTG)), contact("0000 8888"), "fr", PICKUP, actor, key);
	}

	@Test
	void identicalRetryReturnsTheOriginalReservationWithoutTouchingStockAgain() {
		var product = stockedMattress(10);
		var key = key();
		var first = reservations.submit(command(product, 3, key, Actor.guest()));
		var retry = reservations.submit(command(product, 3, key, Actor.guest()));

		assertThat(retry.id()).isEqualTo(first.id());
		assertThat(retry.reference()).isEqualTo(first.reference());
		assertThat(inventory.get(product.id(), null).reserved()).isEqualTo(3);
	}

	@Test
	void theSameKeyWithADifferentPayloadIsRefused() {
		var product = stockedMattress(10);
		var key = key();
		reservations.submit(command(product, 3, key, Actor.guest()));
		assertThatThrownBy(() -> reservations.submit(command(product, 4, key, Actor.guest())))
				.isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("idempotency_key_reuse");
		assertThat(inventory.get(product.id(), null).reserved()).isEqualTo(3);
	}

	@Test
	void keysAreScopedByActorAndByOperation() {
		var product = stockedMattress(10);
		var key = key();
		var staffA = Actor.staff(UUID.randomUUID());
		var staffB = Actor.staff(UUID.randomUUID());
		var a = reservations.submit(command(product, 1, key, staffA));
		var b = reservations.submit(command(product, 1, key, staffB));      // same key, different actor: independent
		assertThat(b.id()).isNotEqualTo(a.id());
		// the same key reused for a DIFFERENT operation is also independent of the submission above
		var ready = ready(a);
		var advanced = reservations.accept(b.id(), staffB, key);
		assertThat(advanced.id()).isEqualTo(b.id());
		assertThat(ready.id()).isEqualTo(a.id());
	}

	@Test
	void aTransitionRetriedWithItsKeyIsHarmless_andWithoutOneItIsRefusedByTheLifecycle() {
		var product = stockedMattress(10);
		var r = submit(key(), line(product, 2, SupportedCurrency.HTG));
		var key = key();
		var first = reservations.accept(r.id(), staff, key);
		var retried = reservations.accept(r.id(), staff, key);    // replay: no exception, same result
		assertThat(retried.status()).isEqualTo(first.status());
		assertThat(retried.history()).hasSameSizeAs(first.history());
		assertThatThrownBy(() -> reservations.accept(r.id(), staff, key())).extracting("code").isEqualTo("invalid_status"); // a NEW attempt is a real second accept
	}

	@Test
	void malformedKeysAreRejected() {
		assertThatThrownBy(() -> new IdempotencyRequest("anonymous", "op", "short", "f")).extracting("code").isEqualTo("idempotency_key_invalid");
		assertThatThrownBy(() -> new IdempotencyRequest("anonymous", "op", "has spaces in the key", "f")).extracting("code").isEqualTo("idempotency_key_invalid");
		assertThatThrownBy(() -> new IdempotencyRequest("anonymous", "op", "x".repeat(200), "f")).extracting("code").isEqualTo("idempotency_key_invalid");
	}

	@Test
	void aFailedOperationDoesNotConsumeTheKey() {
		var product = stockedMattress(1);
		var key = key();
		assertThatThrownBy(() -> reservations.submit(command(product, 5, key, Actor.guest()))).extracting("code").isEqualTo("insufficient_stock");
		// the rollback also removed the key row: the same key can be retried successfully once stock allows
		var ok = reservations.submit(command(product, 1, key, Actor.guest()));
		assertThat(ok.id()).isNotNull();
	}

	@Test
	void expiredKeysAreCleanedUpAndCleanupIsIdempotent() {
		var product = stockedMattress(10);
		var key = key();
		var first = reservations.submit(command(product, 1, key, Actor.guest()));
		jpaIdempotency.cleanupExpired();
		jpaIdempotency.cleanupExpired();   // running it twice is harmless
		// retention is zero: the key has been forgotten, so the same key creates a NEW reservation (documented retention trade-off)
		var again = reservations.submit(command(product, 1, key, Actor.guest()));
		assertThat(again.id()).isNotEqualTo(first.id());
	}
}
