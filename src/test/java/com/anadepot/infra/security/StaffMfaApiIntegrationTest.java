package com.anadepot.infra.security;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Staff MFA enforcement ({@code ana.staff.require-mfa=true}). The assurance level comes from the {@code aal} claim of the SIGNED token (tokens here are signed with a local key, so this proves
 * our enforcement, not Supabase's issuing of {@code aal2}: see docs/auth.md).
 */
@TestPropertySource(properties = "ana.staff.require-mfa=true")
class StaffMfaApiIntegrationTest extends ApiTestSupport {

	private UUID staffMember() {
		var id = UUID.randomUUID();
		staffDirectory.grant(id, null, "mfa test staff");
		return id;
	}

	@Test
	void aStaffMemberWithoutASecondFactorReachesNothingInTheAdministrationButCanSeeTheyNeedOne() throws Exception {
		var id = staffMember();
		for (var weak : new String[] { token(id), token(id, Map.of("aal", "aal1")), token(id, Map.of("aal", "AAL2")), token(id, Map.of("user_metadata", Map.of("aal", "aal2"), "app_metadata", Map.of("aal", "aal2"))) }) {
			get("/api/v1/admin/settings/pickup", weak).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("mfa_required"));
			get("/api/v1/admin/staff", weak).andExpect(status().isForbidden());
			get("/api/v1/me", weak).andExpect(status().isOk()).andExpect(jsonPath("$.staff").value(false)).andExpect(jsonPath("$.mfaRequired").value(true));
		}
	}

	@Test
	void aSessionThatPassedASecondFactorIsFullStaff() throws Exception {
		var id = staffMember();
		var strong = token(id, Map.of("aal", "aal2"));
		get("/api/v1/admin/settings/pickup", strong).andExpect(status().isOk());
		get("/api/v1/me", strong).andExpect(jsonPath("$.staff").value(true)).andExpect(jsonPath("$.mfaRequired").value(false));
	}

	@Test
	void customersAreNeverAskedForASecondFactorAndGuestsAreUnaffected() throws Exception {
		var customer = token(UUID.randomUUID());
		get("/api/v1/me", customer).andExpect(status().isOk()).andExpect(jsonPath("$.staff").value(false)).andExpect(jsonPath("$.mfaRequired").value(false));
		get("/api/v1/admin/settings/pickup", customer).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
		get("/api/v1/store/pickup", null).andExpect(status().isOk());
	}
}
