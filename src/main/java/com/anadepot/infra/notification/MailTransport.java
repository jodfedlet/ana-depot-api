package com.anadepot.infra.notification;

/** How a rendered message leaves the application. */
interface MailTransport {

	/** @throws MailFailure with a stable code when the message was not accepted. Returning normally means the transport accepted it (SMTP: the server accepted it; capture: written to a file). */
	void send(OutgoingMail mail);

	/** True when returning normally means a real mail server accepted the message (status SENT); false when nothing is sent (local capture: status CAPTURED). */
	boolean delivers();
}
