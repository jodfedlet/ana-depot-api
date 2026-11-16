package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.infra.ratelimit.ClientIpResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

	private MockHttpServletRequest request(String peer, String forwardedFor) {
		var r = new MockHttpServletRequest();
		r.setRemoteAddr(peer);
		if (forwardedFor != null) {
			r.addHeader("X-Forwarded-For", forwardedFor);
		}
		return r;
	}

	@Test
	void aForgedHeaderFromAnUntrustedPeerIsIgnored() {
		var resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
		assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
		assertThat(new ClientIpResolver(List.of()).resolve(request("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9"); // no trusted proxies configured
	}

	@Test
	void behindATrustedProxyTheNearestUntrustedHopIsTheClient() {
		var resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
		assertThat(resolver.resolve(request("10.0.0.5", "198.51.100.7"))).isEqualTo("198.51.100.7");
		// the client tried to prepend a fake address: reading from the right ignores it
		assertThat(resolver.resolve(request("10.0.0.5", "6.6.6.6, 198.51.100.7, 10.0.0.9"))).isEqualTo("198.51.100.7");
		assertThat(resolver.resolve(request("10.0.0.5", null))).isEqualTo("10.0.0.5");
		assertThat(resolver.resolve(request("10.0.0.5", "garbage"))).isEqualTo("garbage"); // opaque string: still one bucket, not a bypass
	}
}
