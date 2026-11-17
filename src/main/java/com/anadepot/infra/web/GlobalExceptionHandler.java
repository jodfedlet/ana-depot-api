package com.anadepot.infra.web;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.NotFoundException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error contract: RFC 9457 {@code application/problem+json} with a stable machine-readable {@code code} and the
 * {@code correlationId}. Messages for rule violations are safe to show; unexpected errors never leak internals.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Rule violations that mean "the state of things conflicts with this request" (409) rather than "your input is invalid" (422). */
	private static final Set<String> CONFLICTS = Set.of("insufficient_stock", "invalid_status", "already_paid", "already_reversed", "duplicate_sku",
			"duplicate_currency_price", "payment_required", "idempotency_conflict", "idempotency_key_reuse", "inventory_exists", "concurrent_update",
			"duplicate_slug", "category_has_active_products", "attribute_in_use", "option_in_use", "product_in_use", "variant_axis_in_use", "attribute_type_locked", "attribute_required_violation", "variant_in_use");

	/** A dependency (object storage) is down or not configured: the caller's request was fine, try again later (503). */
	private static final Set<String> UNAVAILABLE = Set.of("storage_unavailable", "storage_not_configured");

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, e.code(), e.getMessage());
	}

	@ExceptionHandler(com.anadepot.infra.ratelimit.RateLimitExceededException.class)
	ResponseEntity<ProblemDetail> rateLimited(com.anadepot.infra.ratelimit.RateLimitExceededException e) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
		problem.setProperty("code", "rate_limited");
		problem.setProperty("correlationId", MDC.get("correlationId"));
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, e.retryAfter().toSeconds()))).body(problem);
	}

	@ExceptionHandler(FieldValidationException.class)
	ResponseEntity<ProblemDetail> fields(FieldValidationException e) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "The request has invalid fields.");
		problem.setProperty("code", e.code());
		problem.setProperty("errors", e.issues().stream().map(i -> java.util.Map.of("field", i.field(), "code", i.code())).toList());
		problem.setProperty("correlationId", MDC.get("correlationId"));
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
	}

	/** Bean-validation failures: field + a stable code (the constraint name), never the framework's message text. */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(org.springframework.web.bind.MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is not valid.");
		problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
				.map(f -> java.util.Map.of("field", f.getField(), "code", snake(f.getCode()))).toList());
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	private static String snake(String code) {
		return code == null ? "invalid" : code.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
	}

	@ExceptionHandler(DomainRuleViolation.class)
	ResponseEntity<ProblemDetail> rule(DomainRuleViolation e) {
		if (UNAVAILABLE.contains(e.code())) {
			return problem(HttpStatus.SERVICE_UNAVAILABLE, e.code(), e.getMessage());
		}
		return problem(CONFLICTS.contains(e.code()) ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage());
	}

	/**
	 * Every standard Spring MVC error (404 no handler, 400 bad parameter/body, 405, 415, validation...) is already a
	 * ProblemDetail via the parent class; this adds our stable {@code code} and the {@code correlationId} to all of them.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		if (body instanceof ProblemDetail problem) {
			problem.setProperty("code", switch (status.value()) {
				case 400 -> problem.getProperties() != null && problem.getProperties().containsKey("errors") ? "validation_failed" : "bad_request";
				case 404 -> "not_found";
				case 405 -> "method_not_allowed";
				case 406 -> "not_acceptable";
				case 415 -> "unsupported_media_type";
				default -> "http_" + status.value();
			});
			problem.setProperty("correlationId", MDC.get("correlationId"));
		}
		return super.handleExceptionInternal(ex, body, headers, status, request);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> concurrent(OptimisticLockingFailureException e) {
		return problem(HttpStatus.CONFLICT, "concurrent_update", "The resource was changed by someone else. Reload and try again.");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e) {
		log.warn("Data integrity violation", e);
		return problem(HttpStatus.CONFLICT, "data_conflict", "The request conflicts with existing data.");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> unexpected(Exception e) {
		log.error("Unexpected error", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong. Quote the correlation id if you contact support.");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		var problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		problem.setProperty("correlationId", MDC.get("correlationId"));
		return ResponseEntity.status(status).body(problem);
	}
}
