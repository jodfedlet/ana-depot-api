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

	private static final String RESERVATIONS = "/api/v1/admin/reservations";

	private JsonNode invite(String staffToken, String note) throws Exception {
		return JSON.readTree(post(STAFF + "/invitations", Map.of("note", note, "role", "STAFF"), staffToken, null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private int redeem(UUID user, String code) throws Exception {
		return post("/api/v1/me/staff-invitation", Map.of("code", code), token(user), null).andReturn().getResponse().getStatus();
	}

	@Test
	void onlyStaffManageTheTeam() throws Exception {
		get(STAFF, null).andExpect(status().isUnauthorized());
		var customer = token(UUID.randomUUID());
		get(STAFF, customer).andExpect(status().isForbidden());
		post(STAFF + "/invitations", Map.of("note", "x", "role", "STAFF"), customer, null).andExpect(status().isForbidden());
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
		get(RESERVATIONS, token(colleague)).andExpect(status().isForbidden());
		assertThat(redeem(colleague, code.toLowerCase())).isEqualTo(200);       // case and dashes do not matter
		get(RESERVATIONS, token(colleague)).andExpect(status().isOk());          // now staff, on their very next request (the invitation said STAFF: counter work only)
		get("/api/v1/me", token(colleague)).andExpect(jsonPath("$.staff").value(true)).andExpect(jsonPath("$.role").value("STAFF"));
		get(STAFF, token(colleague)).andExpect(status().isForbidden());          // the team page is the owner's

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
		post(STAFF + "/invitations", Map.of("note", "   ", "role", "STAFF"), owner, null).andExpect(status().isUnprocessableEntity());
		post(STAFF + "/invitations", Map.of("note", "x".repeat(81), "role", "STAFF"), owner, null).andExpect(status().isUnprocessableEntity());
		post(STAFF + "/invitations", Map.of("note", "x".repeat(80), "role", "STAFF"), owner, null).andExpect(status().isCreated());
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
	void simultaneousRedemptionsOfOneCodeGrantExactlyOneMember() throws Exception {
		var owner = newStaffToken();
		var code = invite(owner, "race").get("code").asString();
		var contenders = new java.util.ArrayList<UUID>();
		for (int i = 0; i < 8; i++) {
			contenders.add(UUID.randomUUID());
		}
		var start = new java.util.concurrent.CountDownLatch(1);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(contenders.size());
		var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
		for (var user : contenders) {
			futures.add(pool.submit(() -> {
				start.await();
				return redeem(user, code);
			}));
		}
		start.countDown();
		var statuses = new java.util.ArrayList<Integer>();
		for (var f : futures) {
			statuses.add(f.get());
		}
		pool.shutdown();
		assertThat(statuses.stream().filter(st -> st == 200).count()).as(statuses.toString()).isEqualTo(1);
		assertThat(statuses.stream().filter(st -> st == 404).count()).isEqualTo(7);
		int staffNow = 0;
		for (var user : contenders) {
			if (mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(RESERVATIONS).header("Authorization", "Bearer " + token(user))).andReturn().getResponse().getStatus() == 200) {
				staffNow++;
			}
		}
		assertThat(staffNow).isEqualTo(1);                       // exactly one of the eight became a team member
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

	@Test
	void theRoleOfAnInvitationIsTheRoleOfTheNewMember_andOnlyAnOwnerSeesTheTeam() throws Exception {
		var owner = newStaffToken();
		var manager = UUID.randomUUID();
		var code = JSON.readTree(post(STAFF + "/invitations", Map.of("note", "Marie, manager", "role", "MANAGER"), owner, null).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("MANAGER"))
				.andReturn().getResponse().getContentAsString()).get("code").asString();
		assertThat(redeem(manager, code)).isEqualTo(200);
		get("/api/v1/me", token(manager)).andExpect(jsonPath("$.role").value("MANAGER")).andExpect(jsonPath("$.permissions").isArray())
				.andExpect(jsonPath("$.permissions[?(@=='CATALOG_MANAGE')]").exists()).andExpect(jsonPath("$.permissions[?(@=='TEAM_MANAGE')]").doesNotExist());
		get(STAFF, token(manager)).andExpect(status().isForbidden());
		post(STAFF + "/invitations", Map.of("note", "sneaky", "role", "OWNER"), token(manager), null).andExpect(status().isForbidden());
		get(STAFF, owner).andExpect(jsonPath("$[?(@.userId=='" + manager + "')].role").value("MANAGER"));
		post(STAFF + "/invitations", Map.of("note", "no role"), owner, null).andExpect(status().isBadRequest());
		post(STAFF + "/invitations", Map.of("note", "bad role", "role", "SUPERUSER"), owner, null).andExpect(status().is4xxClientError());
	}

	@Test
	void anOwnerChangesARoleAndTheLastOwnerIsProtected_andTheNewRightsApplyOnTheVeryNextRequest() throws Exception {
		var ownerId = UUID.randomUUID();
		staffDirectory.grant(ownerId, null, "only owner");
		var owner = token(ownerId);
		var counter = UUID.randomUUID();
		staffDirectory.grant(counter, ownerId, "counter", com.anadepot.core.staff.model.StaffRole.STAFF);
		get("/api/v1/admin/reports/overview", token(counter)).andExpect(status().isForbidden());
		put(STAFF + "/" + counter + "/role", Map.of("role", "MANAGER"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"));
		get("/api/v1/admin/reports/overview", token(counter)).andExpect(status().isOk());       // promoted: allowed now, no new sign-in needed
		put(STAFF + "/" + counter + "/role", Map.of("role", "STAFF"), owner).andExpect(status().isOk());
		get("/api/v1/admin/reports/overview", token(counter)).andExpect(status().isForbidden()); // demoted: refused on the next request
		put(STAFF + "/" + ownerId + "/role", Map.of("role", "MANAGER"), owner).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("cannot_change_own_access"));
		// a second owner exists: the first may now be demoted by the second
		put(STAFF + "/" + counter + "/role", Map.of("role", "OWNER"), owner).andExpect(status().isOk());
		put(STAFF + "/" + ownerId + "/role", Map.of("role", "STAFF"), token(counter)).andExpect(status().isOk());
		put(STAFF + "/" + ownerId + "/role", Map.of("role", "OWNER"), owner).andExpect(status().isForbidden());   // a STAFF member cannot manage the team
		put(STAFF + "/" + counter + "/role", Map.of("role", "BANANA"), token(counter)).andExpect(status().is4xxClientError());
		put(STAFF + "/" + UUID.randomUUID() + "/role", Map.of("role", "STAFF"), token(counter)).andExpect(status().isNotFound());
	}

	@Test
	void theLastActiveOwnerCannotBeRemovedEvenByAnotherOwnerWhoIsAboutToBeRemoved() throws Exception {
		var a = UUID.randomUUID();
		var b = UUID.randomUUID();
		staffDirectory.grant(a, null, "owner a");
		staffDirectory.grant(b, null, "owner b");
		put(STAFF + "/" + b, Map.of("active", false), token(a)).andExpect(status().isOk());                   // two owners -> one
		put(STAFF + "/" + a, Map.of("active", false), token(b)).andExpect(status().isForbidden());            // b is no longer staff at all
		var m = UUID.randomUUID();
		staffDirectory.grant(m, a, "manager", com.anadepot.core.staff.model.StaffRole.MANAGER);
		put(STAFF + "/" + a, Map.of("active", false), token(m)).andExpect(status().isForbidden());            // managers cannot touch the team
		assertThat(staffDirectory.activeRole(a)).contains(com.anadepot.core.staff.model.StaffRole.OWNER);
	}
}
