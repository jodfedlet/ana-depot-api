package com.anadepot.core.notification.repository;

import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationQuery;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port for notification persistence. Claiming is safe for several workers (rows are locked, skipping the ones another worker holds). */
public interface NotificationRepository {

	/** Inserts the notification unless one with the same dedupe key already exists. @return true when inserted. */
	boolean insertIfAbsent(Notification notification);

	/** Notifications created for this recipient hash since {@code since}, excluding skipped ones (used to throttle one address). */
	long countRecentForRecipient(String recipientHash, Instant since);

	/**
	 * Locks up to {@code limit} messages that are due (PENDING/RETRY) or whose lease expired (a worker died), oldest first, marks them PROCESSING with a lease until {@code leaseUntil}
	 * and counts the attempt. The claim commits before anything is sent: no row is locked while a mail server is contacted.
	 */
	List<Notification> claimDue(Instant now, int limit, Instant leaseUntil);

	Optional<Notification> find(UUID id);

	Optional<Notification> findForUpdate(UUID id);

	Notification save(Notification notification);

	PageResult<Notification> list(NotificationQuery query, PageRequest page);

	Map<NotificationStatus, Long> counts();

	/** Creation time of the oldest message still waiting; null when none (feeds the backlog alert). */
	Instant oldestWaiting();
}
