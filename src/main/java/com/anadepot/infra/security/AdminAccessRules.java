package com.anadepot.infra.security;

import com.anadepot.core.staff.model.Permission;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * THE table that says which {@link Permission} each staff endpoint needs (D14). Order matters: the first matching rule wins, so specific paths come before general ones.
 * Anything under {@code /api/v1/admin} that is not listed needs the OWNER role: a new endpoint is closed to Managers and counter staff until someone decides otherwise.
 * {@code AdminAccessMatrixIntegrationTest} walks every mapped admin handler and fails if one is not covered by an explicit rule below.
 */
final class AdminAccessRules {

	private static final String A = "/api/v1/admin";

	private AdminAccessRules() {
	}

	static void apply(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry a) {
		// payments of ONE reservation (counter work) and the money corrections (Owner, Manager)
		a.requestMatchers(HttpMethod.POST, A + "/reservations/*/payments").hasAuthority(perm(Permission.PAYMENTS_RECORD));
		a.requestMatchers(HttpMethod.GET, A + "/reservations/*/payments", A + "/reservations/*/ledger", A + "/reservations/*/reconciliation").hasAuthority(perm(Permission.PAYMENTS_RECORD));
		a.requestMatchers(HttpMethod.GET, A + "/payment-attempts").hasAuthority(perm(Permission.PAYMENTS_RECORD));
		a.requestMatchers(HttpMethod.POST, A + "/reservations/*/payment-adjustments", A + "/payments/*/reversal").hasAuthority(perm(Permission.PAYMENTS_CORRECT));
		a.requestMatchers(HttpMethod.GET, A + "/payments", A + "/payments/*").hasAuthority(perm(Permission.REPORTS_VIEW));

		a.requestMatchers(A + "/reservations/**").hasAuthority(perm(Permission.RESERVATIONS));

		a.requestMatchers(HttpMethod.GET, A + "/inventory", A + "/inventory/**").hasAuthority(perm(Permission.INVENTORY_VIEW));
		a.requestMatchers(A + "/inventory", A + "/inventory/**").hasAuthority(perm(Permission.INVENTORY_ADJUST));

		a.requestMatchers(A + "/products", A + "/products/**", A + "/categories", A + "/categories/**", A + "/catalog/**").hasAuthority(perm(Permission.CATALOG_MANAGE));

		a.requestMatchers(HttpMethod.GET, A + "/customers", A + "/customers/**").hasAuthority(perm(Permission.CUSTOMERS_VIEW));
		a.requestMatchers(HttpMethod.PUT, A + "/customers/*").hasAuthority(perm(Permission.CUSTOMERS_EDIT));

		a.requestMatchers(HttpMethod.GET, A + "/reports/**").hasAuthority(perm(Permission.REPORTS_VIEW));

		a.requestMatchers(HttpMethod.GET, A + "/notifications", A + "/notifications/**").hasAuthority(perm(Permission.NOTIFICATIONS_VIEW));
		a.requestMatchers(HttpMethod.POST, A + "/notifications/*/retry").hasAuthority(perm(Permission.NOTIFICATIONS_RESEND));

		a.requestMatchers(A + "/settings/**").hasAuthority(perm(Permission.SETTINGS_MANAGE));
		a.requestMatchers(A + "/staff", A + "/staff/**").hasAuthority(perm(Permission.TEAM_MANAGE));

		// closed by default: an endpoint nobody listed is for the owner only
		a.requestMatchers(A + "/**").hasAuthority(StaffAuthoritiesConverter.ROLE_PREFIX + "OWNER");
	}

	private static String perm(Permission p) {
		return StaffAuthoritiesConverter.permission(p);
	}
}
