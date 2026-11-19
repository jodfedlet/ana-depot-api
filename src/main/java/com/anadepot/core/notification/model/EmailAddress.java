package com.anadepot.core.notification.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Plain-Java helpers for an e-mail address: validation (deliberately strict, no display names or comments), a masked form for screens and a one-way hash for throttling. */
public final class EmailAddress {

	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

	private EmailAddress() {
	}

	public static String normalize(String raw) {
		return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
	}

	public static boolean isValid(String raw) {
		var e = normalize(raw);
		return !e.isEmpty() && e.length() <= 254 && !e.contains("..") && !e.startsWith(".") && VALID.matcher(e).matches();
	}

	/** "marie.dupont@example.com" -> "m***@example.com": enough for staff to recognise a typo, not enough to reuse the address. */
	public static String mask(String raw) {
		var e = normalize(raw);
		int at = e.indexOf('@');
		if (at < 1) {
			return "***";
		}
		return e.charAt(0) + "***" + e.substring(at);
	}

	public static String hash(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalize(raw).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
