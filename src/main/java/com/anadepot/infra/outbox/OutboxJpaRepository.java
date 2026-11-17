package com.anadepot.infra.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

interface OutboxJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

	/**
	 * Events ready for delivery: due and PENDING, or PROCESSING with an expired lease (a dispatcher died). Locked with
	 * FOR UPDATE SKIP LOCKED so concurrent dispatchers never claim the same row. An event is held back while an EARLIER
	 * event of the same aggregate is still PENDING/PROCESSING (per-aggregate ordering); a DEAD one does not block.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("""
			select e from OutboxEventEntity e
			where ((e.status = 'PENDING' and e.nextAttemptAt <= :now) or (e.status = 'PROCESSING' and e.leaseUntil < :now))
			  and not exists (select 1 from OutboxEventEntity p
			                  where p.aggregateId = e.aggregateId and p.seq < e.seq and p.status in ('PENDING', 'PROCESSING') and p.id <> e.id
			                    and not (p.status = 'PROCESSING' and p.leaseUntil < :now))
			order by e.seq
			""")
	List<OutboxEventEntity> claimDue(Instant now, Pageable page);

	long countByStatus(String status);

	/** Creation time of the oldest event that is still waiting (PENDING) or in flight (PROCESSING); null when nothing is waiting. Feeds the backlog-age alert. */
	@Query("select min(e.createdAt) from OutboxEventEntity e where e.status in ('PENDING', 'PROCESSING')")
	Instant oldestUndelivered();

	List<OutboxEventEntity> findByAggregateIdOrderBySeq(UUID aggregateId);

	@Modifying
	@Query("delete from OutboxEventEntity e where e.status = 'PROCESSED' and e.processedAt < :before")
	int deleteProcessedBefore(Instant before);
}
