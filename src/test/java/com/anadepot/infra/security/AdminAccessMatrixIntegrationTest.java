package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.staff.model.StaffRole;
import com.anadepot.infra.ApiTestSupport;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * D14 (owner-approved): EVERY mapped staff endpoint x EVERY role, over the real security chain. The expectations below are the approved table written out independently of
 * {@code AdminAccessRules}; an endpoint that matches no rule here fails the test, so a new endpoint cannot ship without someone deciding who may call it.
 * Allowed means "not refused by authorization" (the probe sends an unusable body, so nothing is changed); forbidden means exactly 403.
 */
class AdminAccessMatrixIntegrationTest extends ApiTestSupport {

	private static final Set<StaffRole> ALL = EnumSet.allOf(StaffRole.class);
	private static final Set<StaffRole> MANAGER_UP = EnumSet.of(StaffRole.OWNER, StaffRole.MANAGER);
	private static final Set<StaffRole> OWNER_ONLY = EnumSet.of(StaffRole.OWNER);

	private record Rule(Pattern pattern, Set<StaffRole> roles) {
	}

	/** "METHOD path" is matched against the pattern; the first rule that matches wins. */
	private static final List<Rule> RULES = List.of(
			rule("POST /api/v1/admin/reservations/[^/]+/payment-adjustments", MANAGER_UP),
			rule("POST /api/v1/admin/payments/[^/]+/reversal", MANAGER_UP),
			rule("GET /api/v1/admin/payments(/[^/]+)?", MANAGER_UP),
			rule("(GET|POST) /api/v1/admin/reservations/[^/]+/(payments|ledger|reconciliation)", ALL),
			rule("GET /api/v1/admin/payment-attempts", ALL),
			rule("(GET|POST) /api/v1/admin/me/recovery-codes", ALL),   // a member's own recovery codes
			rule("[A-Z]+ /api/v1/admin/reservations(/.*)?", ALL),
			rule("GET /api/v1/admin/inventory(/.*)?", ALL),
			rule("[A-Z]+ /api/v1/admin/inventory(/.*)?", MANAGER_UP),
			rule("[A-Z]+ /api/v1/admin/(products|categories|catalog)(/.*)?", MANAGER_UP),
			rule("GET /api/v1/admin/customers(/.*)?", ALL),
			rule("PUT /api/v1/admin/customers/[^/]+", MANAGER_UP),
			rule("GET /api/v1/admin/reports(/.*)?", MANAGER_UP),
			rule("GET /api/v1/admin/notifications(/.*)?", ALL),
			rule("POST /api/v1/admin/notifications/[^/]+/retry", MANAGER_UP),
			rule("[A-Z]+ /api/v1/admin/settings(/.*)?", OWNER_ONLY),
			rule("[A-Z]+ /api/v1/admin/staff(/.*)?", OWNER_ONLY));

	private static Rule rule(String regex, Set<StaffRole> roles) {
		return new Rule(Pattern.compile(regex), roles);
	}

	@Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

	private record Endpoint(String method, String path) {
		@Override
		public String toString() {
			return method + " " + path;
		}
	}

	private List<Endpoint> adminEndpoints() {
		var found = new ArrayList<Endpoint>();
		handlerMapping.getHandlerMethods().keySet().forEach(info -> {
			var patterns = info.getPathPatternsCondition() == null ? Set.<String>of() : info.getPathPatternsCondition().getPatternValues();
			for (var pattern : patterns) {
				if (pattern.startsWith("/api/v1/admin")) {
					var methods = info.getMethodsCondition().getMethods();
					assertThat(methods).as("handler without an explicit HTTP method: " + pattern).isNotEmpty();
					methods.forEach(m -> found.add(new Endpoint(m.name(), pattern)));
				}
			}
		});
		return found;
	}

	private static Set<StaffRole> expected(Endpoint e) {
		var concrete = e.path().replaceAll("\\{[^}]+}", "x");
		return RULES.stream().filter(r -> r.pattern().matcher(e.method() + " " + concrete).matches()).findFirst().map(Rule::roles)
				.orElseThrow(() -> new AssertionError("No access expectation for " + e + ": decide who may call it and add it to this table AND AdminAccessRules"));
	}

	@Test
	void everyStaffEndpointIsAllowedToExactlyTheRolesTheApprovedTableNames() throws Exception {
		var endpoints = adminEndpoints();
		assertThat(endpoints).as("the staff API was found").hasSizeGreaterThan(50);
		var tokens = new java.util.EnumMap<StaffRole, String>(StaffRole.class);
		for (var role : StaffRole.values()) {
			tokens.put(role, newStaffToken(role));
		}
		var wrong = new ArrayList<String>();
		for (var endpoint : endpoints) {
			var allowed = expected(endpoint);
			var path = endpoint.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
			for (var role : StaffRole.values()) {
				int status = probe(endpoint.method(), path, tokens.get(role));
				boolean refused = status == 403;
				if (allowed.contains(role) == refused || status == 401) {
					wrong.add(endpoint + " as " + role + " -> " + status + " (expected " + (allowed.contains(role) ? "allowed" : "403") + ")");
				}
			}
			// no token at all: never reaches a handler
			assertThat(probe(endpoint.method(), path, null)).as(endpoint + " without a token").isEqualTo(401);
			// a signed-in customer (not staff) is refused everywhere
			assertThat(probe(endpoint.method(), path, token(UUID.randomUUID()))).as(endpoint + " as a customer").isEqualTo(403);
		}
		assertThat(wrong).as("endpoints whose access differs from the approved table").isEmpty();
	}

	@Test
	void everyRoleHasAtLeastOneEndpointItMayNotCall_exceptTheOwner_andTheTableIsNotVacuous() {
		var endpoints = adminEndpoints();
		for (var role : StaffRole.values()) {
			long refused = endpoints.stream().filter(e -> !expected(e).contains(role)).count();
			if (role == StaffRole.OWNER) {
				assertThat(refused).isZero();
			} else {
				assertThat(refused).as(role + " must be restricted somewhere").isGreaterThan(0);
			}
		}
		assertThat(endpoints.stream().filter(e -> expected(e).equals(OWNER_ONLY))).isNotEmpty();
	}

	private int probe(String method, String path, String bearer) throws Exception {
		var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path).header("Idempotency-Key", UUID.randomUUID().toString());
		if (!method.equals("GET") && !method.equals("DELETE")) {
			request.contentType(MediaType.APPLICATION_JSON).content("[]");   // unusable on purpose: a handler that is reached answers 400 and changes nothing
		}
		return send(request, bearer).andReturn().getResponse().getStatus();
	}
}
