package com.anadepot.infra.web;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error contract: RFC 9457 {@code application/problem+json} with a stable machine-readable {@code code} and the
 * {@code correlationId}. Messages for rule violations are safe to show; unexpected errors never leak internals.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Rule violations that mean "the state of things conflicts with this request" (409) rather than "your input is invalid" (422). */
	private static final Set<String> CONFLICTS = Set.of("insufficient_stock", "invalid_status", "already_paid", "already_reversed", "duplicate_sku",
			"duplicate_currency_price", "payment_required", "idempotency_conflict", "idempotency_key_reuse", "inventory_exists", "concurrent_update");

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, e.code(), e.getMessage());
	}

	@ExceptionHandler(DomainRuleViolation.class)
	ResponseEntity<ProblemDetail> rule(DomainRuleViolation e) {
		return problem(CONFLICTS.contains(e.code()) ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is not valid.");
		problem.setProperty("code", "validation_failed");
		problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream().map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))).toList());
		problem.setProperty("correlationId", MDC.get("correlationId"));
		return ResponseEntity.badRequest().body(problem);
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
