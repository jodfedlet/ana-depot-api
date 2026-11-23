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
 *
 * <p>The database is PLAIN PostgreSQL: no Supabase (or Azure/AWS) roles, schemas or extensions. Two switches re-run the very same suite under another
 * standard-PostgreSQL configuration, without any change to the application (docs/database-providers.md, ADR 0016):
 * <ul>
 *   <li>{@code -Dana.test.postgres.image=postgres:16-alpine}: another PostgreSQL major version (default {@value #DEFAULT_IMAGE});</li>
 *   <li>{@code -Dana.test.postgres.restricted-role=true}: Flyway and the application connect as an ordinary role that owns its database and is neither
 *       superuser nor {@code BYPASSRLS} (the role model of hosted services), instead of the container's superuser.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

	static final String DEFAULT_IMAGE = "postgres:17-alpine";
	static final String IMAGE = System.getProperty("ana.test.postgres.image", DEFAULT_IMAGE);
	static final boolean RESTRICTED_ROLE = Boolean.getBoolean("ana.test.postgres.restricted-role");
	private static final String APP_ROLE = "ana_app";
	private static final String APP_ROLE_PASSWORD = "ana_app_test_only";
	private static final String APP_DATABASE = "ana_compat";

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE).withCommand("postgres", "-c", "max_connections=400")
			.withInitScript(RESTRICTED_ROLE ? "provider-compat/restricted-role.sql" : "provider-compat/probe-role.sql");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> RESTRICTED_ROLE
				? "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + APP_DATABASE
				: POSTGRES.getJdbcUrl());
		registry.add("spring.datasource.username", () -> RESTRICTED_ROLE ? APP_ROLE : POSTGRES.getUsername());
		registry.add("spring.datasource.password", () -> RESTRICTED_ROLE ? APP_ROLE_PASSWORD : POSTGRES.getPassword());
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

	/** Submits through the use case for a given phone number. */
	protected Reservation submit(String key, SubmitReservationCommand.Line line, String phone) {
		return reservations.submit(new SubmitReservationCommand(List.of(line), contact(phone), "fr", PICKUP, Actor.guest(), key));
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
