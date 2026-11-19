package com.anadepot.infra.notification;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

interface NotificationJpaRepository extends JpaRepository<NotificationEntity, UUID> {

	boolean existsByDedupeKey(String dedupeKey);

	/** Due messages, or messages whose worker died (expired lease). FOR UPDATE SKIP LOCKED: concurrent workers never claim the same row. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("""
			select n from NotificationEntity n
			where ((n.status in ('PENDING', 'RETRY') and n.nextAttemptAt <= :now) or (n.status = 'PROCESSING' and n.leaseUntil < :now))
			order by n.createdAt
			""")
	List<NotificationEntity> claimDue(@Param("now") Instant now, Pageable page);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select n from NotificationEntity n where n.id = :id")
	Optional<NotificationEntity> lockById(@Param("id") UUID id);

	@Query("select count(n) from NotificationEntity n where n.recipientHash = :hash and n.createdAt >= :since and n.status not like 'SKIPPED%'")
	long countRecent(@Param("hash") String hash, @Param("since") Instant since);

	@Query("""
			select n from NotificationEntity n
			where (:status is null or n.status = :status) and (:type is null or n.type = :type) and (:reservationId is null or n.reservationId = :reservationId)
			order by n.createdAt desc
			""")
	Page<NotificationEntity> search(@Param("status") String status, @Param("type") String type, @Param("reservationId") UUID reservationId, Pageable page);

	@Query("select n.status, count(n) from NotificationEntity n group by n.status")
	List<Object[]> countByStatus();

	@Query("select min(n.createdAt) from NotificationEntity n where n.status in ('PENDING', 'RETRY', 'PROCESSING')")
	Instant oldestWaiting();

	@Query("select r.id, r.reference from ReservationEntity r where r.id in :ids")
	List<Object[]> referencesOf(@Param("ids") java.util.Collection<UUID> ids);
}
