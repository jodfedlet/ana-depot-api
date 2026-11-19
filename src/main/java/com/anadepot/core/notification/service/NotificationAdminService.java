package com.anadepot.core.notification.service;

import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationQuery;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.Map;
import java.util.UUID;

/** Staff visibility and the one controlled action: retrying a message that gave up. */
public interface NotificationAdminService {

	PageResult<Notification> list(NotificationQuery query, PageRequest page);

	Map<NotificationStatus, Long> summary();

	/** Only a DEAD message can be retried (409 otherwise). Audited. The message goes back to the queue with a fresh attempt budget. */
	Notification retry(UUID id, Actor actor);
}
