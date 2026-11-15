package com.anadepot.infra.web;

import com.anadepot.infra.config.MdcCorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id: the client's {@code X-Correlation-Id} if it is well formed, else a new one.
 * It is put in the logging context, echoed in the response and copied onto audit and outbox records.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		var supplied = request.getHeader(HEADER);
		var id = supplied != null && supplied.matches("[A-Za-z0-9_\\-]{8,64}") ? supplied : UUID.randomUUID().toString();
		MDC.put(MdcCorrelationContext.MDC_KEY, id);
		response.setHeader(HEADER, id);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MdcCorrelationContext.MDC_KEY);
		}
	}
}
