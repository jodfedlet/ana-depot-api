package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Second-factor recovery codes over HTTP with {@code ana.staff.require-mfa=true}: the real security chain, real use cases and PostgreSQL. Tokens are signed with a local test key
 * (NOT Supabase): they carry {@code aal} and {@code session_id} exactly as Supabase puts them in the signed token.
 */
@TestPropertySource(properties = { "ana.staff.require-mfa=true", "ana.staff.recovery-session-ttl=PT3S" })
class StaffRecoveryApiIntegrationTest extends ApiTestSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String CODES = "/api/v1/admin/me/recovery-codes";
	private static final String REDEEM = "/api/v1/me/staff-recovery/redeem";
	private static final String ADMIN_PROBE = "/api/v1/admin/reservations";

	@Autowired com.anadepot.infra.persistence.AuditEventJpaRepository audits;

	private UUID member() {
		var id = UUID.randomUUID();
		staffDirectory.grant(id, null, "recovery test");
		return id;
	}

	private String strong(UUID id) {
		return token(id, Map.of("aal", "aal2", "session_id", "strong-" + id));
	}

	private String weak(UUID id, String session) {
		return token(id, Map.of("aal", "aal1", "session_id", session));
	}

	private JsonNode generate(UUID id) throws Exception {
		return JSON.readTree(post(CODES, Map.of(), strong(id), null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	@Test
	void aSessionWithoutTheSecondFactorCannotMakeCodes_theOneWithItDoes_andOnlyTheirOwn() throws Exception {
		var id = member();
		post(CODES, Map.of(), weak(id, "s1"), null).andExpect(status().isForbidden());
		get(CODES, weak(id, "s1")).andExpect(status().isForbidden());
		var made = generate(id);
		assertThat(made.get("codes")).hasSize(10);
		assertThat(made.get("remaining").asInt()).isEqualTo(10);
		get(CODES, strong(id)).andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(10)).andExpect(jsonPath("$.codes").doesNotExist());
		get(CODES, token(UUID.randomUUID())).andExpect(status().isForbidden());   // a signed-in non-member
		// the team list shows the count, never the codes
		var owner = strong(member());
		var team = get("/api/v1/admin/staff", owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(team).contains("\"recoveryCodesRemaining\"").doesNotContain(made.get("codes").get(0).asString());
	}

	@Test
	void aRecoveryCodeLetsOnlyTheSessionThatRedeemedItInForAShortTime_andOnlyOnce() throws Exception {
		var id = member();
		var codes = generate(id).get("codes");
		var phone = weak(id, "lost-phone-session");
		get(ADMIN_PROBE, phone).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("mfa_required"));

		post(REDEEM, Map.of("code", codes.get(0).asString().toLowerCase()), phone, null).andExpect(status().isOk()).andExpect(jsonPath("$.recovered").value(true)).andExpect(jsonPath("$.remaining").value(9));
		get(ADMIN_PROBE, phone).andExpect(status().isOk());
		get("/api/v1/me", phone).andExpect(jsonPath("$.staff").value(true)).andExpect(jsonPath("$.mfaRecovered").value(true)).andExpect(jsonPath("$.mfaRequired").value(false));

		// another sign-in of the same person has gained nothing
		var other = weak(id, "another-session");
		get(ADMIN_PROBE, other).andExpect(status().isForbidden());
		// the code cannot be used twice, even by the same account on another session
		post(REDEEM, Map.of("code", codes.get(0).asString()), other, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("recovery_code_invalid"));
		get(ADMIN_PROBE, other).andExpect(status().isForbidden());

		// the recovered session may make a fresh set (it will need one); the old unused codes stop working
		var again = JSON.readTree(post(CODES, Map.of(), phone, null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		post(REDEEM, Map.of("code", codes.get(1).asString()), other, null).andExpect(status().isNotFound());
		post(REDEEM, Map.of("code", again.get("codes").get(0).asString()), other, null).andExpect(status().isOk());
		get(ADMIN_PROBE, other).andExpect(status().isOk());

		// the grant is short: it ends by itself
		Thread.sleep(3500);
		get(ADMIN_PROBE, phone).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("mfa_required"));
		get("/api/v1/me", phone).andExpect(jsonPath("$.staff").value(false)).andExpect(jsonPath("$.mfaRequired").value(true));
	}

	@Test
	void aCodeIsNoUseToSomeoneElse_aTokenWithoutASessionIdAndNonMembers() throws Exception {
		var id = member();
		var code = generate(id).get("codes").get(0).asString();
		var intruder = member();
		post(REDEEM, Map.of("code", code), weak(intruder, "x"), null).andExpect(status().isNotFound());   // bound to its member
		post(REDEEM, Map.of("code", code), token(id, Map.of("aal", "aal1")), null).andExpect(status().isNotFound());   // no session_id in the token: nothing to bind the grant to
		post(REDEEM, Map.of("code", code), token(UUID.randomUUID(), Map.of("session_id", "y")), null).andExpect(status().isNotFound());   // not a member
		post(REDEEM, Map.of("code", code), null, null).andExpect(status().isUnauthorized());
		post(REDEEM, Map.of(), weak(id, "z"), null).andExpect(status().isBadRequest());
		get(ADMIN_PROBE, weak(id, "z")).andExpect(status().isForbidden());
		// and the code still works for its owner afterwards: failures consumed nothing
		post(REDEEM, Map.of("code", code), weak(id, "z"), null).andExpect(status().isOk());
	}

	@Test
	void everyUseIsAuditedWithoutTheCode_andAnAuthenticatorSessionNeverNeedsOne() throws Exception {
		var id = member();
		var code = generate(id).get("codes").get(0).asString();
		post(REDEEM, Map.of("code", "AAAA-BBBB-CCCC-DDDD"), weak(id, "a"), null).andExpect(status().isNotFound());
		post(REDEEM, Map.of("code", code), weak(id, "a"), null).andExpect(status().isOk());
		var actions = audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("staff_member", id.toString()).stream().map(a -> a.getAction()).toList();
		assertThat(actions).contains("STAFF_RECOVERY_CODES_GENERATED", "STAFF_MFA_RECOVERY_REFUSED", "STAFF_MFA_RECOVERY_USED");
		var everything = audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("staff_member", id.toString()).toString();
		assertThat(everything).doesNotContain(code).doesNotContain(code.replace("-", ""));
		get(ADMIN_PROBE, strong(id)).andExpect(status().isOk());
		get("/api/v1/me", strong(id)).andExpect(jsonPath("$.mfaRecovered").value(false));
	}

	@Test
	void redeemingIsRateLimitedPerAccount() throws Exception {
		var id = member();
		generate(id);
		int last = 0;
		for (int i = 0; i < 7; i++) {
			last = post(REDEEM, Map.of("code", "AAAA-BBBB-CCCC-DDDD"), weak(id, "rl"), null).andReturn().getResponse().getStatus();
		}
		assertThat(last).isEqualTo(429);
	}
}
