package com.anadepot.infra.report;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.payment.model.LedgerEntryType;
import com.anadepot.core.report.model.ReportRows;
import com.anadepot.core.report.repository.ReportRepository;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregation for reports, in JPQL over the existing entities (referenced by entity name: this class owns no mapping). Sums are computed per
 * currency by grouping on the currency column; nothing here adds amounts of different currencies.
 */
@Component
@Transactional(readOnly = true)
class ReportRepositoryAdapter implements ReportRepository {

	private static final List<ReservationStatus> OPEN = List.of(ReservationStatus.PENDING_REVIEW, ReservationStatus.CONFIRMED, ReservationStatus.READY_FOR_PICKUP);

	@PersistenceContext
	private EntityManager em;

	@Override
	public Map<ReservationStatus, Long> reservationsCreated(Instant from, Instant to) {
		var result = new EnumMap<ReservationStatus, Long>(ReservationStatus.class);
		em.createQuery("select r.status, count(r) from ReservationEntity r where r.createdAt >= :from and r.createdAt < :to group by r.status", Object[].class)
				.setParameter("from", from).setParameter("to", to).getResultList().forEach(row -> result.put((ReservationStatus) row[0], (Long) row[1]));
		return result;
	}

	@Override
	public Map<ReservationStatus, Long> openNow() {
		var result = new EnumMap<ReservationStatus, Long>(ReservationStatus.class);
		em.createQuery("select r.status, count(r) from ReservationEntity r where r.status in :open group by r.status", Object[].class).setParameter("open", OPEN).getResultList()
				.forEach(row -> result.put((ReservationStatus) row[0], (Long) row[1]));
		return result;
	}

	@Override
	public List<ReportRows.Collected> collected(Instant from, Instant to) {
		var rows = new ArrayList<ReportRows.Collected>();
		em.createQuery("select r.id, h.changedAt, t.currency, t.totalAmount from ReservationStatusHistoryEntity h join h.reservation r join r.totals t "
				+ "where h.toStatus = :collected and h.changedAt >= :from and h.changedAt < :to", Object[].class)
				.setParameter("collected", ReservationStatus.COLLECTED).setParameter("from", from).setParameter("to", to).getResultList()
				.forEach(row -> rows.add(new ReportRows.Collected((UUID) row[0], (Instant) row[1], Money.of((BigDecimal) row[3], (SupportedCurrency) row[2]))));
		return rows;
	}

	@Override
	public List<ReportRows.LedgerMovement> ledger(Instant from, Instant to) {
		var rows = new ArrayList<ReportRows.LedgerMovement>();
		em.createQuery("select e.createdAt, e.entryType, e.currency, e.amount from LedgerEntryEntity e where e.createdAt >= :from and e.createdAt < :to", Object[].class)
				.setParameter("from", from).setParameter("to", to).getResultList()
				.forEach(row -> rows.add(new ReportRows.LedgerMovement((Instant) row[0], (LedgerEntryType) row[1], Money.of((BigDecimal) row[3], (SupportedCurrency) row[2]))));
		return rows;
	}

	@Override
	public List<ReportRows.ReceivedByMethod> receivedByMethod(Instant from, Instant to) {
		var rows = new ArrayList<ReportRows.ReceivedByMethod>();
		em.createQuery("select p.paymentMethod, p.currency, sum(p.amount) from PaymentEntity p where p.recordedAt >= :from and p.recordedAt < :to group by p.paymentMethod, p.currency", Object[].class)
				.setParameter("from", from).setParameter("to", to).getResultList()
				.forEach(row -> rows.add(new ReportRows.ReceivedByMethod((com.anadepot.core.payment.model.PaymentMethod) row[0], Money.of((BigDecimal) row[2], (SupportedCurrency) row[1]))));
		return rows;
	}

	@Override
	public List<ReportRows.ProductSales> productSales(Instant from, Instant to) {
		var rows = new ArrayList<ReportRows.ProductSales>();
		em.createQuery("select l.productId, max(l.productName), max(l.productSku), l.currency, sum(l.quantity), sum(l.lineTotal), count(distinct r.id) "
				+ "from ReservationLineEntity l join l.reservation r where r.id in (select h.reservation.id from ReservationStatusHistoryEntity h "
				+ "where h.toStatus = :collected and h.changedAt >= :from and h.changedAt < :to) group by l.productId, l.currency", Object[].class)
				.setParameter("collected", ReservationStatus.COLLECTED).setParameter("from", from).setParameter("to", to).getResultList()
				.forEach(row -> rows.add(new ReportRows.ProductSales((UUID) row[0], (String) row[1], (String) row[2], (SupportedCurrency) row[3], ((Number) row[4]).longValue(),
						Money.of((BigDecimal) row[5], (SupportedCurrency) row[3]), (Long) row[6])));
		return rows;
	}

	@Override
	public Map<String, Long> rejectionReasons(Instant from, Instant to) {
		var result = new HashMap<String, Long>();
		em.createQuery("select coalesce(h.reasonCode, 'UNSPECIFIED'), count(h) from ReservationStatusHistoryEntity h join h.reservation r "
				+ "where h.toStatus = :rejected and r.createdAt >= :from and r.createdAt < :to group by h.reasonCode", Object[].class)
				.setParameter("rejected", ReservationStatus.REJECTED).setParameter("from", from).setParameter("to", to).getResultList()
				.forEach(row -> result.put((String) row[0], (Long) row[1]));
		return result;
	}

	@Override
	public ReportRows.StockCounts stockCounts() {
		long untracked = count("select count(i) from InventoryItemEntity i where i.tracked = false");
		long out = count("select count(i) from InventoryItemEntity i where i.tracked = true and i.onHand - i.reserved <= 0");
		long low = count("select count(i) from InventoryItemEntity i where i.tracked = true and i.onHand - i.reserved > 0 and i.lowStockThreshold is not null and i.onHand - i.reserved <= i.lowStockThreshold");
		long ok = count("select count(i) from InventoryItemEntity i where i.tracked = true and i.onHand - i.reserved > 0 and (i.lowStockThreshold is null or i.onHand - i.reserved > i.lowStockThreshold)");
		long withoutRecord = em.createQuery("select count(p) from ProductEntity p where p.status = :active and not exists (select 1 from InventoryItemEntity i where i.productId = p.id)", Long.class)
				.setParameter("active", ProductStatus.ACTIVE).getSingleResult();
		return new ReportRows.StockCounts(ok, low, out, untracked, withoutRecord);
	}

	@Override
	public long activeProducts() {
		return em.createQuery("select count(p) from ProductEntity p where p.status = :active", Long.class).setParameter("active", ProductStatus.ACTIVE).getSingleResult();
	}

	@Override
	public long newCustomers(Instant from, Instant to) {
		return em.createQuery("select count(c) from CustomerEntity c where c.createdAt >= :from and c.createdAt < :to", Long.class).setParameter("from", from).setParameter("to", to).getSingleResult();
	}

	private long count(String jpql) {
		return em.createQuery(jpql, Long.class).getSingleResult();
	}
}
