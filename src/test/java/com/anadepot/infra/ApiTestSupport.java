package com.anadepot.infra;

import com.anadepot.infra.persistence.Json;
import com.anadepot.infra.security.StaffDirectory;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * HTTP-level tests: the REAL security filter chain, validation, controllers, use cases and PostgreSQL. Only the token SIGNING KEY is local
 * (tokens are signed with a generated RSA key and verified by the production validators); this is NOT a Supabase integration test.
 */
@AutoConfigureMockMvc
@Import(ApiTestSupport.LocalKeys.class)
public abstract class ApiTestSupport extends AbstractIntegrationTest {

	public static final String ISSUER = "https://api-test.supabase.co/auth/v1";
	public static final String AUDIENCE = "authenticated";
	static final KeyPair KEY = newKey();

	@TestConfiguration
	static class LocalKeys {
		@Bean
		@Primary
		JwtDecoder apiTestJwtDecoder() {
			return com.anadepot.infra.security.SupabaseJwtDecoders.fromPublicKey((RSAPublicKey) KEY.getPublic(), ISSUER, AUDIENCE);
		}
	}

	@Autowired protected MockMvc mvc;
	@Autowired protected StaffDirectory staffDirectory;

	static KeyPair newKey() {
		try {
			var g = KeyPairGenerator.getInstance("RSA");
			g.initialize(2048);
			return g.generateKeyPair();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	protected static String token(UUID subject) {
		try {
			var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject.toString()).audience(AUDIENCE).issueTime(Date.from(Instant.now().minusSeconds(5)))
					.expirationTime(Date.from(Instant.now().plusSeconds(600))).claim("email", subject + "@example.test").build();
			var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
			jwt.sign(new RSASSASigner((RSAPrivateKey) KEY.getPrivate()));
			return jwt.serialize();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** A token of a NEW user who is staff (membership recorded in the staff table, as in production). */
	protected String newStaffToken() {
		var id = UUID.randomUUID();
		staffDirectory.grant(id, null, "api test staff");
		return token(id);
	}

	protected static String json(Object value) {
		return Json.write(value);
	}

	protected ResultActions send(MockHttpServletRequestBuilder request, String bearer) throws Exception {
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return mvc.perform(request);
	}

	protected ResultActions get(String path, String bearer) throws Exception {
		return send(MockMvcRequestBuilders.get(path), bearer);
	}

	protected ResultActions post(String path, Object body, String bearer, String idempotencyKey) throws Exception {
		var r = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json(body));
		if (idempotencyKey != null) {
			r.header("Idempotency-Key", idempotencyKey);
		}
		return send(r, bearer);
	}

	protected ResultActions put(String path, Object body, String bearer) throws Exception {
		return send(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json(body)), bearer);
	}
}
