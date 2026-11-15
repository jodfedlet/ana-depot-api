package com.anadepot.core.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic SHA-256 of the parts that make a request "the same request". */
public final class Fingerprint {

	private Fingerprint() {
	}

	public static String of(Object... parts) {
		var sb = new StringBuilder();
		for (var p : parts) {
			var s = String.valueOf(p);
			sb.append(s.length()).append(':').append(s).append('|');
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
