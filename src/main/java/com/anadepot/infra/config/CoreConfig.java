package com.anadepot.infra.config;

import com.anadepot.core.catalog.repository.CategoryRepository;
import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.catalog.repository.UploadIntentRepository;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.catalog.service.UploadService;
import com.anadepot.core.catalog.serviceImpl.CatalogServiceImpl;
import com.anadepot.core.catalog.serviceImpl.UploadServiceImpl;
import com.anadepot.core.customer.repository.CustomerRepository;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.core.inventory.service.InventoryService;
import com.anadepot.core.inventory.serviceImpl.InventoryServiceImpl;
import com.anadepot.core.payment.repository.PaymentRepository;
import com.anadepot.core.payment.service.PaymentService;
import com.anadepot.core.payment.serviceImpl.PaymentServiceImpl;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.reservation.service.ReservationService;
import com.anadepot.core.reservation.serviceImpl.ReservationServiceImpl;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.CorrelationContext;
import com.anadepot.core.shared.DomainEventRecorder;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.OperationMetrics;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one place where {@code core} (plain Java, no Spring) meets Spring: core services are constructed here with
 * infrastructure adapters passed in as their ports.
 */
@Configuration
class CoreConfig {

	@Bean
	@ConditionalOnMissingBean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	CatalogService catalogService(ProductRepository products, CategoryRepository categories, TransactionBoundary tx, AuditRecorder audit,
			com.anadepot.core.catalog.repository.ProductSearchIndex searchIndex) {
		return new CatalogServiceImpl(products, categories, tx, audit, searchIndex);
	}

	@Bean
	InventoryService inventoryService(InventoryRepository inventory, TransactionBoundary tx, Clock clock, AuditRecorder audit, DomainEventRecorder events, IdempotencyService idempotency) {
		return new InventoryServiceImpl(inventory, tx, clock, audit, events, idempotency);
	}

	@Bean
	ReservationService reservationService(ReservationRepository reservations, InventoryRepository inventory, CatalogService catalog, CustomerRepository customers,
			AuditRecorder audit, DomainEventRecorder events, OperationMetrics metrics, IdempotencyService idempotency, TransactionBoundary tx, Clock clock, com.anadepot.core.reservation.repository.PickupSnapshotProvider pickup) {
		return new ReservationServiceImpl(reservations, inventory, catalog, customers, audit, events, metrics, idempotency, tx, clock, pickup);
	}

	@Bean
	com.anadepot.core.customer.service.AccountService accountService(CustomerRepository customers, ReservationRepository reservations, TransactionBoundary tx, Clock clock) {
		return new com.anadepot.core.customer.serviceImpl.AccountServiceImpl(customers, reservations, tx, clock);
	}

	@Bean
	PaymentService paymentService(PaymentRepository payments, ReservationRepository reservations, AuditRecorder audit, DomainEventRecorder events,
			OperationMetrics metrics, CorrelationContext correlation, IdempotencyService idempotency, TransactionBoundary tx, Clock clock) {
		return new PaymentServiceImpl(payments, reservations, audit, events, metrics, correlation, idempotency, tx, clock);
	}

	@Bean
	com.anadepot.core.settings.service.StoreSettingsService storeSettingsService(com.anadepot.core.settings.repository.PickupSettingsRepository repository, AuditRecorder audit,
			DomainEventRecorder events, TransactionBoundary tx, Clock clock) {
		return new com.anadepot.core.settings.serviceImpl.StoreSettingsServiceImpl(repository, audit, events, tx, clock);
	}

	@Bean
	UploadService uploadService(UploadIntentRepository intents, ProductRepository products, ObjectStorage storage, AuditRecorder audit, DomainEventRecorder events,
			IdempotencyService idempotency, TransactionBoundary tx, Clock clock, AnaProperties properties) {
		var u = properties.upload();
		return new UploadServiceImpl(intents, products, storage, audit, events, idempotency, tx, clock, u.urlTtl(), u.maxBytes(), u.allowedContentTypes(), u.abandonedAfter());
	}

	@Bean
	com.anadepot.core.catalog.service.ProductImageService productImageService(ProductRepository products, ObjectStorage storage, AuditRecorder audit, DomainEventRecorder events,
			OperationMetrics metrics, TransactionBoundary tx, Clock clock) {
		return new com.anadepot.core.catalog.serviceImpl.ProductImageServiceImpl(products, storage, audit, events, metrics, tx, clock);
	}
}
