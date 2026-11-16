package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.AttributeDefinition;
import com.anadepot.core.catalog.model.AttributeOption;
import com.anadepot.core.catalog.model.AttributeType;
import com.anadepot.core.catalog.model.AttributeValue;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductPrice;
import com.anadepot.core.catalog.model.ProductPricing;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.model.ProductVariant;
import com.anadepot.core.catalog.model.SearchDocument;
import com.anadepot.core.catalog.model.SellingUnit;
import com.anadepot.core.catalog.model.WholesaleTier;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.core.shared.LocalizedText;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TEST-ONLY generator of a representative catalog: realistic French/Haitian Creole names, SKUs, 2 attributes (a choice and a text), about one in five
 * products with variants, HTG and/or USD prices (some with wholesale tiers), shared stock. Persists through the entities in batches (flush + clear every
 * 200 products) and indexes each product through the real search index port. Deterministic for a given seed. Samples are fictional.
 */
final class CatalogGenerator {

	private static final String[][] NOUNS = {{"Matelas", "Matla"}, {"Chaise", "Chèz"}, {"Table", "Tab"}, {"Soulier", "Soulye"}, {"Tuyau", "Tiyo"}, {"Seau", "Bokit"},
			{"Casserole", "Chodyè"}, {"Drap", "Dra"}, {"Lampe", "Lanp"}, {"Armoire", "Amwa"}, {"Étagère", "Etajè"}, {"Tabouret", "Tabouret"}, {"Ventilateur", "Vantilatè"},
			{"Marmite", "Mamit"}, {"Sandale", "Sandal"}, {"Valise", "Valiz"}, {"Brouette", "Boutèt"}, {"Peinture", "Penti"}, {"Cadenas", "Kadna"}, {"Corde", "Kòd"}};
	private static final String[][] ADJECTIVES = {{"pliante", "pliyan"}, {"renforcée", "ranfòse"}, {"légère", "lejè"}, {"robuste", "solid"}, {"économique", "bon mache"},
			{"professionnelle", "pwofesyonèl"}, {"galvanisée", "galvanize"}, {"ergonomique", "ergonomik"}, {"empilable", "anpile"}, {"étanche", "etanch"},
			{"compacte", "kompak"}, {"traditionnelle", "tradisyonèl"}, {"moderne", "modèn"}, {"réglable", "reglab"}, {"antidérapante", "antiglisad"}};
	private static final String[] BRANDS = {"Karibe", "Soleil", "Citadelle", "Palmiste", "Lakay", "Horizon", "Mapou", "Tropik", "Kreyol Home", "Nord Sud", "Artibonite", "Jacmelia"};
	private static final String[][] COLOURS = {{"Rouge", "Wouj"}, {"Bleu", "Ble"}, {"Vert", "Vèt"}, {"Noir", "Nwa"}, {"Blanc", "Blan"}};
	private static final String[] MATERIALS = {"bois", "acier", "plastique", "coton", "aluminium", "cuir", "mousse", "céramique"};

	private final CatalogService catalog;
	private final InventoryRepository inventory;
	private final ProductSearchNative index;
	private final EntityManager em;
	private final TransactionTemplate tx;
	private final Random random;

	CatalogGenerator(CatalogService catalog, InventoryRepository inventory, ProductSearchNative index, EntityManager em, TransactionTemplate tx, long seed) {
		this.catalog = catalog;
		this.inventory = inventory;
		this.index = index;
		this.em = em;
		this.tx = tx;
		this.random = new Random(seed);
	}

	private record Prepared(Category category, UUID colour, List<UUID> colourOptions, UUID material) {
	}

	List<Category> categories(int count) {
		var out = new ArrayList<Category>();
		for (int i = 0; i < count; i++) {
			var colourId = UUID.randomUUID();
			var options = new ArrayList<AttributeOption>();
			for (int c = 0; c < COLOURS.length; c++) {
				options.add(new AttributeOption(UUID.randomUUID(), "c" + c, new LocalizedText(Map.of("fr", COLOURS[c][0], "ht", COLOURS[c][1])), c, true));
			}
			var colour = new AttributeDefinition(colourId, "couleur", LocalizedText.of("fr", "Couleur"), AttributeType.CHOICE, null, false, true, true, 1, true, options);
			var material = new AttributeDefinition(UUID.randomUUID(), "matiere", LocalizedText.of("fr", "Matière"), AttributeType.TEXT, null, false, false, false, 2, true, List.of());
			out.add(catalog.saveCategory(new Category(UUID.randomUUID(), "gen-" + i + "-" + UUID.randomUUID().toString().substring(0, 6),
					new LocalizedText(Map.of("fr", "Rayon " + NOUNS[i % NOUNS.length][0].toLowerCase(), "ht", "Rayon " + NOUNS[i % NOUNS.length][1].toLowerCase())), LocalizedText.empty(), null, true, i, List.of(colour, material))));
		}
		return out;
	}

	/** Persists {@code count} products (about 85% with stock) spread over the categories. */
	void products(int count, List<Category> categories, String skuPrefix) {
		for (int start = 0; start < count; start += 200) {
			int from = start;
			tx.executeWithoutResult(s -> {
				var batch = new ArrayList<Product>();
				for (int i = from; i < Math.min(count, from + 200); i++) {
					var category = categories.get(random.nextInt(categories.size()));
					var product = product(i, category, skuPrefix);
					var entity = new ProductEntity();
					entity.id = product.id();
					entity.createdAt = product.createdAt();
					CatalogMapper.apply(product, entity);
					em.persist(entity);
					batch.add(product);
				}
				em.flush();
				for (var p : batch) {
					var category = categories.stream().filter(c -> c.id().equals(p.categoryId())).findFirst().orElseThrow();
					index.index(p.id(), SearchDocument.of(p, category));
					if (random.nextInt(100) < 85) {
						if (p.hasVariants()) {
							p.variants().forEach(v -> inventory.save(new InventoryItem(UUID.randomUUID(), p.id(), v.id(), true, null, 1 + random.nextInt(50), 0)));
						} else {
							inventory.save(new InventoryItem(UUID.randomUUID(), p.id(), null, true, null, 1 + random.nextInt(50), 0));
						}
					}
				}
				em.flush();
				em.clear();
			});
		}
	}

	private Product product(int i, Category category, String skuPrefix) {
		var noun = NOUNS[random.nextInt(NOUNS.length)];
		var adj = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
		var size = 40 + random.nextInt(200);
		var fr = noun[0] + " " + adj[0] + " " + size;
		var ht = noun[1] + " " + adj[1] + " " + size;
		var prices = new ArrayList<ProductPrice>();
		boolean htg = random.nextInt(100) < 75;
		boolean usd = !htg || random.nextInt(100) < 55;
		if (htg) {
			prices.add(priced(SupportedCurrency.HTG, 500 + random.nextInt(60_000)));
		}
		if (usd) {
			prices.add(priced(SupportedCurrency.USD, 5 + random.nextInt(500)));
		}
		var variants = new ArrayList<ProductVariant>();
		var colour = category.attributes().getFirst();
		if (random.nextInt(5) == 0) {
			for (int v = 0; v < 2 + random.nextInt(2); v++) {
				var option = colour.options().get(v);
				variants.add(new ProductVariant(UUID.randomUUID(), skuPrefix + "-" + i + "-V" + v, true, v, Map.of(colour.id(), option.id()), Map.of()));
			}
		}
		var attrs = List.of(AttributeValue.choice(colour.id(), colour.options().get(random.nextInt(colour.options().size())).id()),
				AttributeValue.text(category.attributes().get(1).id(), MATERIALS[random.nextInt(MATERIALS.length)]));
		return new Product(UUID.randomUUID(), String.format("%s-%06d", skuPrefix, i), skuPrefix.toLowerCase() + "-" + i, category.id(), new LocalizedText(Map.of("fr", fr, "ht", ht)),
				LocalizedText.of("fr", noun[0] + " " + adj[0] + " de qualité, idéale pour la maison, le commerce et les professionnels."), BRANDS[random.nextInt(BRANDS.length)],
				SellingUnit.UNIT, LocalizedText.empty(), ProductStatus.ACTIVE, ProductPricing.of(prices), variants, attrs, List.of(), Instant.now().minusSeconds(random.nextInt(10_000_000)), Instant.now());
	}

	private ProductPrice priced(SupportedCurrency currency, int retail) {
		var base = Money.of(BigDecimal.valueOf(retail), currency);
		var tiers = random.nextInt(3) == 0 ? List.of(new WholesaleTier(5, Money.of(BigDecimal.valueOf(Math.max(1, retail * 9 / 10)), currency))) : List.<WholesaleTier>of();
		return new ProductPrice(currency, base, null, 1, !tiers.isEmpty(), tiers, true);
	}
}
