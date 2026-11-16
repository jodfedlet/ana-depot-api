package com.anadepot.infra;

import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.inventory.service.InventoryService;
import com.anadepot.core.payment.service.PaymentService;
import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.service.ReservationService;
import com.anadepot.core.reservation.service.SubmitReservationCommand;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.testsupport.TestData;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL (Testcontainers) with the production Flyway migrations. ONE container is shared by every test class
 * in the run (it is never stopped between contexts); tests create their own uniquely-named data.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withCommand("postgres", "-c", "max_connections=400");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	protected static final String PICKUP = "{\"label\":\"pickup snapshot used in tests\"}";

	@Autowired protected CatalogService catalog;
	@Autowired protected InventoryService inventory;
	@Autowired protected ReservationService reservations;
	@Autowired protected PaymentService payments;
	@Autowired protected com.anadepot.core.settings.service.StoreSettingsService storeSettings;

	protected final Actor staff = Actor.staff(UUID.randomUUID());

	/** A mattress sold in HTG and USD with its own tiers, and ONE stock position of {@code onHand} units. */
	protected Product stockedMattress(int onHand) {
		return stockedMattress(onHand, 1, 1);
	}

	protected Product stockedMattress(int onHand, int htgMinOrder, int usdMinOrder) {
		Category category = TestData.category().category();
		catalog.saveCategory(category);
		var product = catalog.saveProduct(TestData.product(category.id(), ProductStatus.ACTIVE,
				TestData.price(TestData.htg("25000.00"), null, htgMinOrder, TestData.tier(5, TestData.htg("23000.00"))),
				TestData.price(TestData.usd("180.00"), null, usdMinOrder, TestData.tier(5, TestData.usd("165.00")))), staff);
		inventory.register(product.id(), null, true, onHand, null, staff.userId());
		return product;
	}

	protected static SubmitReservationCommand.Line line(Product p, int quantity, SupportedCurrency currency) {
		return new SubmitReservationCommand.Line(p.id(), null, quantity, currency);
	}

	protected static Contact contact(String phone) {
		return new Contact("Marie", "Exemple", "+509", phone, null);
	}

	protected Reservation submit(String key, SubmitReservationCommand.Line... lines) {
		return reservations.submit(new SubmitReservationCommand(List.of(lines), contact("0000 " + (1000 + (int) (Math.random() * 8999))), "fr", PICKUP, Actor.guest(), key));
	}

	protected Reservation ready(Reservation r) {
		reservations.accept(r.id(), staff, null);
		return reservations.markReady(r.id(), staff, null, null);
	}

	/** Makes pickup available (with obviously fake test values) so reservations can be submitted through the real flow. */
	protected void ensurePickupAvailable() {
		var current = storeSettings.getPickup();
		if (current.availability() != com.anadepot.core.settings.model.PickupSettings.Availability.AVAILABLE) {
			storeSettings.savePickup(TestData.testPickup(true), current.version(), staff);
		}
	}

	protected static String key() {
		return "key-" + UUID.randomUUID();
	}
}
