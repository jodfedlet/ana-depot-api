package com.anadepot.infra.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Finds the real client address WITHOUT trusting client-supplied headers: {@code X-Forwarded-For} is honoured only when
 * the TCP peer is a configured trusted proxy, and then the chain is read from the RIGHT (nearest hop first), skipping
 * trusted proxies; the first untrusted address is the client. A direct caller sending a forged header is ignored.
 */
public final class ClientIpResolver {

	private final List<IpAddressMatcher> trusted;

	public ClientIpResolver(List<String> trustedProxies) {
		this.trusted = (trustedProxies == null ? List.<String>of() : trustedProxies).stream().map(IpAddressMatcher::new).toList();
	}

	public String resolve(HttpServletRequest request) {
		var peer = request.getRemoteAddr();
		if (!isTrusted(peer)) {
			return peer;
		}
		var header = request.getHeader("X-Forwarded-For");
		if (header == null || header.isBlank()) {
			return peer;
		}
		var hops = header.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			var hop = hops[i].strip();
			if (!hop.isEmpty() && !isTrusted(hop)) {
				return hop;
			}
		}
		return peer;
	}

	private boolean isTrusted(String address) {
		try {
			return trusted.stream().anyMatch(m -> m.matches(address));
		} catch (IllegalArgumentException malformed) {
			return false;
		}
	}
}
