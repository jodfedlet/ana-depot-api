package com.anadepot.core.report.repository;

import com.anadepot.core.report.model.ReportRows;
import com.anadepot.core.reservation.model.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Port: read-only facts for reports. Ranges are {@code [from, to)}. */
public interface ReportRepository {

	/** Reservations created in the range, by their CURRENT status. */
	Map<ReservationStatus, Long> reservationsCreated(Instant from, Instant to);

	/** Reservations that are open right now, by status (not limited to a period). */
	Map<ReservationStatus, Long> openNow();

	/** Every currency total of the reservations collected in the range. */
	List<ReportRows.Collected> collected(Instant from, Instant to);

	/** Every ledger entry created in the range. */
	List<ReportRows.LedgerMovement> ledger(Instant from, Instant to);

	/** Per product and currency: units and revenue of the reservations collected in the range. */
	List<ReportRows.ProductSales> productSales(Instant from, Instant to);

	/** Rejected reservations created in the range, by reject reason code. */
	Map<String, Long> rejectionReasons(Instant from, Instant to);

	ReportRows.StockCounts stockCounts();

	long activeProducts();

	long newCustomers(Instant from, Instant to);
}
