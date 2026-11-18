package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Team management over HTTP: the real security chain, real use cases and PostgreSQL (tokens signed with a local test key, NOT Supabase). */
class StaffManagementApiIntegrationTest extends ApiTestSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String STAFF = "/api/v1/admin/staff";

	private JsonNode invite(String staffToken, String note) throws Exception {
		return JSON.readTree(post(STAFF + "/invitations", Map.of("note", note), staffToken, null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private int redeem(UUID user, String code) throws Exception {
		return post("/api/v1/me/staff-invitation", Map.of("code", code), token(user), null).andReturn().getResponse().getStatus();
	}

	@Test
	void onlyStaffManageTheTeam() throws Exception {
		get(STAFF, null).andExpect(status().isUnauthorized());
		var customer = token(UUID.randomUUID());
		get(STAFF, customer).andExpect(status().isForbidden());
		post(STAFF + "/invitations", Map.of("note", "x"), customer, null).andExpect(status().isForbidden());
		put(STAFF + "/" + UUID.randomUUID(), Map.of("active", false), customer).andExpect(status().isForbidden());
	}

	@Test
	void aCodeIsShownOnceAndAColleagueRedeemsItWithTheirOwnAccount() throws Exception {
		var owner = newStaffToken();
		var issued = invite(owner, "Marie, cash desk");
		var code = issued.get("code").asString();
		assertThat(code).matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){5}");

		// afterwards only the status is visible, never the code
		var list = get(STAFF + "/invitations", owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(list).contains("Marie, cash desk").contains("PENDING").doesNotContain(code).doesNotContain(code.replace("-", ""));

		var colleague = UUID.randomUUID();
		get(STAFF, token(colleague)).andExpect(status().isForbidden());
		assertThat(redeem(colleague, code.toLowerCase())).isEqualTo(200);       // case and dashes do not matter
		get(STAFF, token(colleague)).andExpect(status().isOk());                 // now staff, on their very next request
		get("/api/v1/me", token(colleague)).andExpect(jsonPath("$.staff").value(true));

		// the code is single-use, and a second person cannot reuse it
		assertThat(redeem(UUID.randomUUID(), code)).isEqualTo(404);
		var members = get(STAFF, owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(members).contains(colleague.toString()).contains("Marie, cash desk");
		get(STAFF + "/invitations", owner).andExpect(jsonPath("$[?(@.note=='Marie, cash desk')].status").value("ACCEPTED"));
	}

	@Test
	void badMissingRevokedAndAlreadyUsedCodesAllAnswerTheSameWay() throws Exception {
		var owner = newStaffToken();
		var revoked = invite(owner, "revoked one");
		send(delete(STAFF + "/invitations/" + revoked.get("id").asString()), owner).andExpect(status().isNoContent());
		var user = UUID.randomUUID();
		var answers = new java.util.HashSet<String>();
		for (var code : new String[] { revoked.get("code").asString(), "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF", "short", "" }) {
			var res = post("/api/v1/me/staff-invitation", Map.of("code", code), token(user), null).andReturn().getResponse();
			assertThat(res.getStatus()).isIn(404, 400);   // a blank code is a validation error; every well-formed wrong code is the same 404
			if (res.getStatus() == 404) {
				answers.add(JSON.readTree(res.getContentAsString()).get("code").asString());
			}
		}
		assertThat(answers).containsExactly("invitation_invalid");
		get(STAFF, token(user)).andExpect(status().isForbidden());              // nothing was granted
		send(delete(STAFF + "/invitations/" + revoked.get("id").asString()), owner).andExpect(status().isConflict()); // already revoked
	}

	@Test
	void anInvitationNeedsANoteOfAtMost80Characters() throws Exception {
		var owner = newStaffToken();
		post(STAFF + "/invitations", Map.of("note", "   "), owner, null).andExpect(status().isUnprocessableEntity());
		post(STAFF + "/invitations", Map.of("note", "x".repeat(81)), owner, null).andExpect(status().isUnprocessableEntity());
		post(STAFF + "/invitations", Map.of("note", "x".repeat(80)), owner, null).andExpect(status().isCreated());
	}

	@Test
	void aMemberAlreadyOnTheTeamCannotRedeemAnotherCode() throws Exception {
		var owner = newStaffToken();
		var code = invite(owner, "dup").get("code").asString();
		var existing = UUID.randomUUID();
		staffDirectory.grant(existing, null, "already staff");
		assertThat(redeem(existing, code)).isEqualTo(409);
		assertThat(redeem(UUID.randomUUID(), code)).isEqualTo(200);             // the code was not consumed by the refused attempt
	}

	@Test
	void removingAColleagueEndsTheirAccessAtOnceAndNobodyChangesTheirOwnAccess() throws Exception {
		var ownerId = UUID.randomUUID();
		staffDirectory.grant(ownerId, null, "owner");
		var owner = token(ownerId);
		var colleagueId = UUID.randomUUID();
		staffDirectory.grant(colleagueId, null, "colleague");
		var colleague = token(colleagueId);
		get("/api/v1/admin/settings/pickup", colleague).andExpect(status().isOk());

		put(STAFF + "/" + ownerId, Map.of("active", false), owner).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("cannot_change_own_access"));
		put(STAFF + "/" + colleagueId, Map.of("active", false), owner).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		get("/api/v1/admin/settings/pickup", colleague).andExpect(status().isForbidden());     // the very next request
		put(STAFF + "/" + colleagueId, Map.of("active", true), owner).andExpect(status().isOk());
		get("/api/v1/admin/settings/pickup", colleague).andExpect(status().isOk());
		put(STAFF + "/" + UUID.randomUUID(), Map.of("active", false), owner).andExpect(status().isNotFound());
		put(STAFF + "/" + colleagueId, Map.of(), owner).andExpect(status().isBadRequest());
	}

	@Test
	void redeemingIsRateLimitedPerAccount() throws Exception {
		var user = token(UUID.randomUUID());
		int last = 0;
		for (int i = 0; i < 7; i++) {
			last = post("/api/v1/me/staff-invitation", Map.of("code", "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF"), user, null).andReturn().getResponse().getStatus();
		}
		assertThat(last).isEqualTo(429);
	}
}
