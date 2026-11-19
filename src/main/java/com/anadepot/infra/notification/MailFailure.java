package com.anadepot.infra.notification;

/** A delivery attempt that did not succeed. {@code code} is a short stable identifier (never provider text, never an address or a secret); {@code permanent}: retrying cannot help. */
class MailFailure extends RuntimeException {

	private final String code;
	private final boolean permanent;

	MailFailure(String code, boolean permanent, Throwable cause) {
		super(code);   // the message is only the code: causes may carry addresses or server banners, so they are not copied into logs or the database
		this.code = code;
		this.permanent = permanent;
		if (cause != null) {
			setStackTrace(new StackTraceElement[0]);
		}
	}

	String code() {
		return code;
	}

	boolean permanent() {
		return permanent;
	}
}
