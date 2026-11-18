package com.anadepot.infra.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies per-operation limits keyed by the authenticated user id when there is one, else by client IP. Runs inside the
 * security chain (after the bearer token is read) so authenticated users behind a shared network are not throttled
 * as one IP. Returns 429 with Retry-After and a problem+json body.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private record Rule(RequestMatcher matcher, String operation) {
		Rule(HttpMethod method, String pattern, String operation) {
			this(PathPatternRequestMatcher.withDefaults().matcher(method, pattern), operation);
		}
	}

	/** Operation names are the keys of {@code ana.rate-limit.limits}. Stricter limits belong on the sensitive/expensive ones. */
	private static final List<Rule> RULES = List.of(
			new Rule(HttpMethod.POST, "/api/v1/reservations", "reservation.submit"),
			new Rule(HttpMethod.POST, "/api/v1/reservations/lookup", "reservation.lookup"),   // POST: the phone number must not travel in a URL
			new Rule(HttpMethod.POST, "/api/v1/reservations/*/payments/checkout-session", "payment.checkout"),
			new Rule(HttpMethod.POST, "/api/v1/reservations/*/payments/status", "payment.status"),
			new Rule(HttpMethod.POST, "/api/v1/me/staff-invitation", "staff.accept"),   // redeeming a code: very few tries
			new Rule(HttpMethod.PUT, "/api/v1/me/profile", "account.write"),
			new Rule(HttpMethod.GET, "/api/v1/me/**", "account.read"),
			new Rule(HttpMethod.POST, "/api/v1/admin/products/*/images/uploads", "upload.request"),
			new Rule(HttpMethod.POST, "/api/v1/admin/catalog/search/reindex", "admin.reindex"),
			new Rule(HttpMethod.POST, "/api/v1/admin/**", "admin.write"),
			new Rule(HttpMethod.PUT, "/api/v1/admin/**", "admin.write"),
			new Rule(HttpMethod.PATCH, "/api/v1/admin/**", "admin.write"),
			new Rule(HttpMethod.DELETE, "/api/v1/admin/**", "admin.write"),
			new Rule(HttpMethod.POST, "/api/v1/webhooks/**", "webhook.receive"),
			new Rule(HttpMethod.GET, "/api/v1/products/**", "catalog.read"),
			new Rule(HttpMethod.GET, "/api/v1/categories/**", "catalog.read"));

	private final RateLimiter limiter;
	private final ClientIpResolver ips;

	public RateLimitFilter(RateLimiter limiter, ClientIpResolver ips) {
		this.limiter = limiter;
		this.ips = ips;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		var operation = operationOf(request);
		if (operation != null) {
			var decision = limiter.check(operation, clientKey(request));
			if (!decision.allowed()) {
				response.setStatus(429);
				response.setHeader("Retry-After", String.valueOf(Math.max(1, decision.retryAfter().toSeconds())));
				response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
				response.setCharacterEncoding("UTF-8");
				response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"code\":\"rate_limited\",\"detail\":\"Too many requests. Try again later.\"}");
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private String operationOf(HttpServletRequest request) {
		// text search is the expensive public read: stricter limit than plain browsing
		if ("GET".equals(request.getMethod()) && ("/api/v1/products/search".equals(request.getRequestURI())
				|| ("/api/v1/products".equals(request.getRequestURI()) && request.getParameter("q") != null && !request.getParameter("q").isBlank()))) {
			return "catalog.search";
		}
		for (var rule : RULES) {
			if (rule.matcher().matches(request)) {
				return rule.operation();
			}
		}
		return null;
	}

	private String clientKey(HttpServletRequest request) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
			return "user:" + auth.getName();
		}
		return "ip:" + ips.resolve(request);
	}
}
