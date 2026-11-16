package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.infra.config.AnaProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * Where the signing keys come from: {@code SUPABASE_JWKS_URL} (override) or {@code <issuer>/.well-known/jwks.json}, and how the issuer is derived from
 * {@code SUPABASE_URL}. A tiny local HTTP server plays the key-set endpoint; this is NOT a Supabase integration test.
 */
class SupabaseJwksConfigurationTest {

	private static final String ISSUER = "https://project-ref.supabase.co/auth/v1";

	private HttpServer server;
	private final AtomicInteger customHits = new AtomicInteger();
	private KeyPair trusted;
	private String jwksUrl;

	private static KeyPair newKey() throws Exception {
		var g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		return g.generateKeyPair();
	}

	@BeforeEach
	void start() throws Exception {
		trusted = newKey();
		var set = new JWKSet(new RSAKey.Builder((RSAPublicKey) trusted.getPublic()).keyID("kid-1").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build()).toString();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/private/keys.json", exchange -> {
			customHits.incrementAndGet();
			var bytes = set.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		jwksUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/private/keys.json";
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private static String token(KeyPair signer, String issuer) throws Exception {
		var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(UUID.randomUUID().toString()).audience("authenticated")
				.issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
		var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("kid-1").build(), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) signer.getPrivate()));
		return jwt.serialize();
	}

	@Test
	void theKeySetIsFetchedFromTheOverrideWhenOneIsConfigured() throws Exception {
		var decoder = SupabaseJwtDecoders.fromIssuer(ISSUER, "authenticated", jwksUrl);
		var jwt = decoder.decode(token(trusted, ISSUER));
		assertThat(jwt.getSubject()).isNotBlank();
		assertThat(customHits.get()).isEqualTo(1);
	}

	@Test
	void theOverrideChangesWhereKeysComeFromNotWhatIsTrusted() throws Exception {
		var decoder = SupabaseJwtDecoders.fromIssuer(ISSUER, "authenticated", jwksUrl);
		assertThatThrownBy(() -> decoder.decode(token(newKey(), ISSUER))).isInstanceOf(BadJwtException.class);                    // signed by a key that is not in the set
		assertThatThrownBy(() -> decoder.decode(token(trusted, "https://evil.example/auth/v1"))).isInstanceOf(BadJwtException.class); // the issuer claim is still enforced
	}

	@Test
	void withoutAnOverrideTheDefaultIsTheIssuersWellKnownJwks() {
		assertThat(SupabaseJwtDecoders.jwksUri(ISSUER, null)).isEqualTo(ISSUER + "/.well-known/jwks.json");
		assertThat(SupabaseJwtDecoders.jwksUri(ISSUER + "/", "  ")).isEqualTo(ISSUER + "/.well-known/jwks.json");
		assertThat(SupabaseJwtDecoders.jwksUri(ISSUER, " https://keys.internal/jwks.json ")).isEqualTo("https://keys.internal/jwks.json");
	}

	@Test
	void theIssuerIsDerivedFromTheProjectUrlOnlyWhenNoIssuerIsSet() {
		assertThat(new AnaProperties.Security("https://project-ref.supabase.co/", null, null, "authenticated", null).effectiveIssuer()).isEqualTo(ISSUER);
		assertThat(new AnaProperties.Security("https://project-ref.supabase.co", "", null, "authenticated", null).effectiveIssuer()).isEqualTo(ISSUER);
		assertThat(new AnaProperties.Security("https://project-ref.supabase.co", "https://other/auth/v1", null, "authenticated", null).effectiveIssuer()).isEqualTo("https://other/auth/v1");
		assertThat(new AnaProperties.Security(null, null, null, "authenticated", null).effectiveIssuer()).isNull();
		assertThat(new AnaProperties.Security(" ", " ", null, "authenticated", null).effectiveIssuer()).isNull();
	}

	@Test
	void theProductionDecoderIsBuiltFromTheDerivedIssuerAndTheOverride() throws Exception {
		var props = new AnaProperties(null, null, null, new AnaProperties.Security("https://project-ref.supabase.co", null, jwksUrl, "authenticated", null), null, null);
		var decoder = new SecurityConfig().jwtDecoder(props);
		assertThat(decoder.decode(token(trusted, ISSUER)).getIssuer().toString()).isEqualTo(ISSUER);
	}
}
