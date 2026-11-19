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
	com.anadepot.core.catalog.service.CategoryAdminService categoryAdminService(CategoryRepository categories, ProductRepository products, CatalogService catalog, AuditRecorder audit,
			IdempotencyService idempotency, TransactionBoundary tx, Clock clock) {
		return new com.anadepot.core.catalog.serviceImpl.CategoryAdminServiceImpl(categories, products, catalog, audit, idempotency, tx, clock);
	}

	@Bean
	com.anadepot.core.catalog.service.ProductAdminService productAdminService(ProductRepository products, CategoryRepository categories, CatalogService catalog, InventoryService inventory,
			AuditRecorder audit, IdempotencyService idempotency, TransactionBoundary tx, Clock clock) {
		return new com.anadepot.core.catalog.serviceImpl.ProductAdminServiceImpl(products, categories, catalog, inventory, audit, idempotency, tx, clock);
	}

	@Bean
	com.anadepot.core.inventory.service.InventoryAdminService inventoryAdminService(com.anadepot.core.inventory.repository.InventoryRepository inventoryRepository, InventoryService inventory,
			ProductRepository products, AuditRecorder audit, TransactionBoundary tx) {
		return new com.anadepot.core.inventory.serviceImpl.InventoryAdminServiceImpl(inventoryRepository, inventory, products, audit, tx);
	}

	@Bean
	com.anadepot.core.reservation.service.ReservationAdminService reservationAdminService(ReservationRepository reservations, AuditRecorder audit, IdempotencyService idempotency, TransactionBoundary tx,
			Clock clock) {
		return new com.anadepot.core.reservation.serviceImpl.ReservationAdminServiceImpl(reservations, audit, idempotency, tx, clock);
	}

	@Bean
	com.anadepot.core.payment.service.PaymentAdminService paymentAdminService(com.anadepot.core.payment.repository.PaymentRepository payments, ReservationRepository reservations, TransactionBoundary tx) {
		return new com.anadepot.core.payment.serviceImpl.PaymentAdminServiceImpl(payments, reservations, tx);
	}

	@Bean
	com.anadepot.core.customer.service.CustomerAdminService customerAdminService(CustomerRepository customers, ReservationRepository reservations, AuditRecorder audit, TransactionBoundary tx) {
		return new com.anadepot.core.customer.serviceImpl.CustomerAdminServiceImpl(customers, reservations, audit, tx);
	}

	@Bean
	com.anadepot.core.report.service.ReportService reportService(com.anadepot.core.report.repository.ReportRepository reports, TransactionBoundary tx) {
		return new com.anadepot.core.report.serviceImpl.ReportServiceImpl(reports, tx);
	}

	@Bean
	InventoryService inventoryService(InventoryRepository inventory, TransactionBoundary tx, Clock clock, AuditRecorder audit, DomainEventRecorder events, IdempotencyService idempotency) {
		return new InventoryServiceImpl(inventory, tx, clock, audit, events, idempotency);
	}

	@Bean
	com.anadepot.core.notification.service.NotificationRecorder notificationRecorder(com.anadepot.core.notification.repository.NotificationRepository notifications, Clock clock,
			com.anadepot.infra.notification.MailProperties mail) {
		return new com.anadepot.core.notification.serviceImpl.NotificationRecorderImpl(notifications, clock, mail.perRecipientPerHour());
	}

	@Bean
	com.anadepot.core.notification.service.NotificationAdminService notificationAdminService(com.anadepot.core.notification.repository.NotificationRepository notifications, AuditRecorder audit,
			TransactionBoundary tx, Clock clock) {
		return new com.anadepot.core.notification.serviceImpl.NotificationAdminServiceImpl(notifications, audit, tx, clock);
	}

	@Bean
	ReservationService reservationService(ReservationRepository reservations, InventoryRepository inventory, CatalogService catalog, CustomerRepository customers,
			AuditRecorder audit, DomainEventRecorder events, OperationMetrics metrics, IdempotencyService idempotency, TransactionBoundary tx, Clock clock, com.anadepot.core.reservation.repository.PickupSnapshotProvider pickup,
			com.anadepot.core.notification.service.NotificationRecorder notifications) {
		return new ReservationServiceImpl(reservations, inventory, catalog, customers, audit, events, metrics, idempotency, tx, clock, pickup, notifications);
	}

	@Bean
	com.anadepot.core.customer.service.AccountService accountService(CustomerRepository customers, ReservationRepository reservations, TransactionBoundary tx, Clock clock) {
		return new com.anadepot.core.customer.serviceImpl.AccountServiceImpl(customers, reservations, tx, clock);
	}

	@Bean
	PaymentService paymentService(PaymentRepository payments, ReservationRepository reservations, AuditRecorder audit, DomainEventRecorder events,
			OperationMetrics metrics, CorrelationContext correlation, IdempotencyService idempotency, TransactionBoundary tx, Clock clock, com.anadepot.core.notification.service.NotificationRecorder notifications) {
		return new PaymentServiceImpl(payments, reservations, audit, events, metrics, correlation, idempotency, tx, clock, notifications);
	}

	@Bean
	com.anadepot.core.payment.service.OnlinePaymentService onlinePaymentService(com.anadepot.core.payment.repository.PaymentAttemptRepository attempts, ReservationRepository reservations,
			CustomerRepository customers, PaymentService payments, com.anadepot.core.payment.service.CheckoutGateway gateway, IdempotencyService idempotency, OperationMetrics metrics,
			TransactionBoundary tx, Clock clock, com.anadepot.core.notification.service.NotificationRecorder notifications) {
		return new com.anadepot.core.payment.serviceImpl.OnlinePaymentServiceImpl(attempts, reservations, customers, payments, gateway, idempotency, metrics, tx, clock, notifications);
	}

	@Bean
	com.anadepot.core.settings.service.StoreSettingsService storeSettingsService(com.anadepot.core.settings.repository.PickupSettingsRepository repository, AuditRecorder audit,
			DomainEventRecorder events, TransactionBoundary tx, Clock clock) {
		return new com.anadepot.core.settings.serviceImpl.StoreSettingsServiceImpl(repository, audit, events, tx, clock);
	}

	@Bean
	com.anadepot.core.staff.service.StaffService staffService(com.anadepot.core.staff.repository.StaffRepository repository, AuditRecorder audit, TransactionBoundary tx, Clock clock,
			com.anadepot.infra.security.StaffProperties staffProperties) {
		return new com.anadepot.core.staff.serviceImpl.StaffServiceImpl(repository, audit, tx, clock, java.time.Duration.ofDays(staffProperties.invitationDays()));
	}

	@Bean
	com.anadepot.core.staff.service.StaffRecoveryService staffRecoveryService(com.anadepot.core.staff.repository.StaffRecoveryRepository recovery, com.anadepot.core.staff.repository.StaffRepository staff, AuditRecorder audit,
			TransactionBoundary tx, Clock clock, com.anadepot.infra.security.StaffProperties staffProperties) {
		return new com.anadepot.core.staff.serviceImpl.StaffRecoveryServiceImpl(recovery, staff, audit, tx, clock, staffProperties.recoverySessionTtl());
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
