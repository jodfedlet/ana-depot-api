package com.anadepot.core.notification.service;

import com.anadepot.core.notification.model.NotificationRequest;

/**
 * Called by business use cases INSIDE their transaction: the message row commits or rolls back together with the operation, so a message can neither be lost nor announce something
 * that did not happen. It never sends anything and never talks to a mail server.
 */
public interface NotificationRecorder {

	void record(NotificationRequest request);
}
