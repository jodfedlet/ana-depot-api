package com.anadepot.infra.security;

import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies Supabase-issued access tokens: signature (against the project's published JWKS), issuer, audience, expiry /
 * not-before, and that the subject is a UUID. Supabase-specific code stays in infrastructure. Revocation: access tokens
 * are short-lived and verified offline; a revoked session stops working when its token expires (docs/auth.md).
 */
public final class SupabaseJwtDecoders {

	private SupabaseJwtDecoders() {
	}

	public static JwtDecoder fromIssuer(String issuer, String audience) {
		var jwks = issuer.replaceAll("/+$", "") + "/.well-known/jwks.json";
		var decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
				.jwsAlgorithms(a -> {
					a.add(SignatureAlgorithm.ES256);
					a.add(SignatureAlgorithm.RS256);
				}).build();
		decoder.setJwtValidator(validators(issuer, audience));
		return decoder;
	}

	/** Same validation with a fixed public key: used by tests with locally generated keys (NOT a Supabase integration test). */
	public static JwtDecoder fromPublicKey(RSAPublicKey key, String issuer, String audience) {
		var decoder = NimbusJwtDecoder.withPublicKey(key).build();
		decoder.setJwtValidator(validators(issuer, audience));
		return decoder;
	}

	static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience(audience), subjectIsUuid());
	}

	private static OAuth2TokenValidator<Jwt> audience(String expected) {
		return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expected)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected audience", null));
	}

	private static OAuth2TokenValidator<Jwt> subjectIsUuid() {
		return jwt -> {
			try {
				UUID.fromString(jwt.getSubject());
				return OAuth2TokenValidatorResult.success();
			} catch (RuntimeException e) {
				return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid subject", null));
			}
		};
	}
}
