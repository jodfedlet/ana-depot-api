package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.AbstractIntegrationTest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The REAL security filter chain with the REAL validation rules (signature, issuer, audience, expiry), using tokens signed
 * by a locally generated key. This is NOT an integration test against Supabase: that needs a Supabase project (docs/auth.md).
 */
@AutoConfigureMockMvc
@Import(SecurityIntegrationTest.LocalKeys.class)
class SecurityIntegrationTest extends AbstractIntegrationTest {

	static final String ISSUER = "https://test-project.supabase.co/auth/v1";
	static final String AUDIENCE = "authenticated";
	static final KeyPair TRUSTED = newKey();
	static final KeyPair ATTACKER = newKey();

	@TestConfiguration
	static class LocalKeys {
		@Bean
		@Primary
		JwtDecoder testJwtDecoder() {
			return SupabaseJwtDecoders.fromPublicKey((RSAPublicKey) TRUSTED.getPublic(), ISSUER, AUDIENCE);
		}
	}

	@Autowired MockMvc mvc;
	@Autowired StaffDirectory staffDirectory;

	static KeyPair newKey() {
		try {
			var g = KeyPairGenerator.getInstance("RSA");
			g.initialize(2048);
			return g.generateKeyPair();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String token(KeyPair key, UUID subject, String issuer, String audience, Instant expires, Map<String, Object> extra) {
		try {
			var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject.toString()).audience(audience).issueTime(Date.from(Instant.now().minusSeconds(10)))
					.expirationTime(Date.from(expires)).claim("email", "person@example.com").claim("role", "authenticated");
			extra.forEach(claims::claim);
			var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
			jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
			return jwt.serialize();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String valid(UUID subject) {
		return token(TRUSTED, subject, ISSUER, AUDIENCE, Instant.now().plusSeconds(600), Map.of());
	}

	private ResultActions call(String path, String bearer) throws Exception {
		var request = get(path);
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return mvc.perform(request);
	}

	// ---------------------------------------------------------------- missing / invalid tokens

	@Test
	void noTokenMeansUnauthorized_withAGenericProblemBody() throws Exception {
		call("/api/v1/me", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthenticated")).andExpect(jsonPath("$.status").value(401));
		call("/api/v1/admin/anything", null).andExpect(status().isUnauthorized());
	}

	@Test
	void garbageExpiredWrongIssuerWrongAudienceAndForgedSignaturesAreAllRejected() throws Exception {
		var user = UUID.randomUUID();
		call("/api/v1/me", "not-a-jwt").andExpect(status().isUnauthorized());
		call("/api/v1/me", token(TRUSTED, user, ISSUER, AUDIENCE, Instant.now().minusSeconds(60), Map.of())).andExpect(status().isUnauthorized());              // expired
		call("/api/v1/me", token(TRUSTED, user, "https://evil.example/auth/v1", AUDIENCE, Instant.now().plusSeconds(600), Map.of())).andExpect(status().isUnauthorized()); // wrong issuer
		call("/api/v1/me", token(TRUSTED, user, ISSUER, "service_role", Instant.now().plusSeconds(600), Map.of())).andExpect(status().isUnauthorized());          // wrong audience
		call("/api/v1/me", token(ATTACKER, user, ISSUER, AUDIENCE, Instant.now().plusSeconds(600), Map.of())).andExpect(status().isUnauthorized());              // signed by someone else
		call("/api/v1/me", token(TRUSTED, UUID.randomUUID(), ISSUER, AUDIENCE, Instant.now().plusSeconds(600), Map.of()).replaceAll("\\.[^.]+$", ".AAAA")).andExpect(status().isUnauthorized()); // tampered signature
		var notUuid = new JWTClaimsSet.Builder().issuer(ISSUER).subject("admin").audience(AUDIENCE).expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), notUuid);
		jwt.sign(new RSASSASigner((RSAPrivateKey) TRUSTED.getPrivate()));
		call("/api/v1/me", jwt.serialize()).andExpect(status().isUnauthorized());                                                                              // subject is not a user id
	}

	@Test
	void unalgorithmedTokensAreRejected() throws Exception {
		var header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
		var payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + UUID.randomUUID() + "\",\"aud\":\"authenticated\"}").getBytes());
		call("/api/v1/me", header + "." + payload + ".").andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- customer vs staff

	@Test
	void aValidCustomerTokenIsAuthenticatedButNotStaff() throws Exception {
		var user = UUID.randomUUID();
		call("/api/v1/me", valid(user)).andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(user.toString()))
				.andExpect(jsonPath("$.email").value("person@example.com"))
				.andExpect(jsonPath("$.staff").value(false))
				.andExpect(jsonPath("$.permissions").isEmpty())
				.andExpect(jsonPath("$.customerProfileId").doesNotExist());
		call("/api/v1/admin/anything", valid(user)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
	}

	@Test
	void staffStatusComesFromTheStaffTableOnly() throws Exception {
		var user = UUID.randomUUID();
		var staffToken = valid(user);
		call("/api/v1/admin/anything", staffToken).andExpect(status().isForbidden());

		staffDirectory.grant(user, null, "test staff");

		call("/api/v1/me", staffToken).andExpect(jsonPath("$.staff").value(true));
		call("/api/v1/admin/anything", staffToken).andExpect(status().isNotFound()); // authorized: it reaches routing (no such admin endpoint yet)
	}

	@Test
	void claimsClaimingToBeAdminAreIgnored_userMetadataAndAppMetadataGrantNothing() throws Exception {
		var user = UUID.randomUUID();
		var sneaky = token(TRUSTED, user, ISSUER, AUDIENCE, Instant.now().plusSeconds(600),
				Map.of("app_metadata", Map.of("role", "admin", "staff", true), "user_metadata", Map.of("is_admin", true, "role", "staff"), "staff", true, "is_admin", true, "roles", List.of("STAFF", "ADMIN")));
		call("/api/v1/me", sneaky).andExpect(jsonPath("$.staff").value(false));
		call("/api/v1/admin/anything", sneaky).andExpect(status().isForbidden());
	}

	@Test
	void aDeactivatedStaffMemberLosesAccessImmediately() throws Exception {
		var user = UUID.randomUUID();
		staffDirectory.grant(user, null, "will be deactivated");
		call("/api/v1/admin/anything", valid(user)).andExpect(status().isNotFound());
		staffDirectory.deactivate(user);
		call("/api/v1/admin/anything", valid(user)).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- deny by default / public catalog

	@Test
	void everythingNotExplicitlyAllowedIsDenied_andTheCatalogIsPublic() throws Exception {
		call("/api/v1/unknown", null).andExpect(status().isUnauthorized());
		call("/api/v1/unknown", valid(UUID.randomUUID())).andExpect(status().isForbidden());
		call("/v3/api-docs", null).andExpect(status().isUnauthorized());
		call("/actuator/env", null).andExpect(status().isUnauthorized());
		call("/actuator/metrics", null).andExpect(status().isUnauthorized());
		call("/actuator/health", null).andExpect(status().isOk());
		call("/api/v1/products", null).andExpect(status().isOk());   // browsing needs no account
	}

	@Test
	void aCustomerCannotReadStaffOnlyMetrics_staffCan() throws Exception {
		var user = UUID.randomUUID();
		call("/actuator/metrics", valid(user)).andExpect(status().isForbidden());
		staffDirectory.grant(user, null, "ops");
		call("/actuator/metrics", valid(user)).andExpect(status().isOk());
	}

	@Test
	void noTokenSurvivesWhenAuthenticationIsNotConfigured() {
		// the production decoder with no issuer configured rejects everything (fail closed)
		var decoder = new SecurityConfig().jwtDecoder(new com.anadepot.infra.config.AnaProperties(null, null, null, new com.anadepot.infra.config.AnaProperties.Security(null, null, null, "authenticated", null), null, null, null));
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> decoder.decode(valid(UUID.randomUUID()))).isInstanceOf(org.springframework.security.oauth2.jwt.BadJwtException.class);
		assertThat(decoder).isNotNull();
	}
}
