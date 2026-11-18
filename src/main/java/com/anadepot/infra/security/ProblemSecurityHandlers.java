package com.anadepot.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 401/403 as problem+json. Deliberately generic: never says WHY a token was rejected. */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
		write(response, 401, "Unauthorized", "unauthenticated", "Authentication is required.");
		response.setHeader("WWW-Authenticate", "Bearer");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
		var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
		boolean mfa = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(StaffAuthoritiesConverter.MFA_REQUIRED));
		if (mfa) {
			write(response, 403, "Forbidden", "mfa_required", "A second verification step is required before using the administration.");
		} else {
			write(response, 403, "Forbidden", "forbidden", "You are not allowed to perform this operation.");
		}
	}

	private static void write(HttpServletResponse response, int status, String title, String code, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
	}
}
