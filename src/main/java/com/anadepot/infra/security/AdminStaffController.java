package com.anadepot.infra.security;

import com.anadepot.core.staff.model.StaffInvitation;
import com.anadepot.core.staff.model.StaffMember;
import com.anadepot.core.staff.model.StaffRole;
import com.anadepot.infra.web.QueryParams;
import com.anadepot.core.staff.service.StaffService;
import com.anadepot.infra.customer.CustomerJpaRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The team: who works for the store and how a colleague joins. Staff only (SecurityConfig). Membership is all-or-nothing (roles are an open business decision).
 * The invitation code is returned ONCE, when it is created; afterwards only its status is visible.
 */
@RestController
@RequestMapping("/api/v1/admin/staff")
class AdminStaffController {

	/** {@code initial}: the first administrator, provisioned from configuration rather than invited (no inviter). */
	record MemberView(UUID userId, boolean active, String role, boolean you, boolean initial, String displayName, String email, String note, Instant joinedAt, Instant updatedAt) {

		/** Personal data: never printed. */
		@Override
		public String toString() {
			return "MemberView[redacted]";
		}
	}

	record InvitationView(UUID id, String note, String role, String status, Instant createdAt, Instant expiresAt) {
	}

	record IssuedView(UUID id, String note, String role, Instant expiresAt, String code) {

		@Override
		public String toString() {
			return "IssuedView[code=hidden]";
		}
	}

	record InviteRequest(@NotNull String note, @NotNull String role) {
	}

	record RoleRequest(@NotNull String role) {
	}

	record ActiveRequest(@NotNull Boolean active) {
	}

	private final StaffService staff;
	private final CustomerJpaRepository customers;
	private final Clock clock;

	AdminStaffController(StaffService staff, CustomerJpaRepository customers, Clock clock) {
		this.staff = staff;
		this.customers = customers;
		this.clock = clock;
	}

	@GetMapping
	List<MemberView> members(Authentication authentication) {
		var members = staff.members();
		Map<UUID, CustomerJpaRepository.AccountName> names = new HashMap<>();
		customers.findNamesByAuthUserIds(members.stream().map(StaffMember::userId).toList()).forEach(n -> names.put(n.authUserId(), n));
		return members.stream().map(m -> view(m, names.get(m.userId()), authentication.getName())).toList();
	}

	@PutMapping("/{userId}")
	MemberView setActive(@PathVariable UUID userId, @Valid @RequestBody ActiveRequest body, Authentication authentication) {
		var saved = staff.setActive(userId, body.active(), Actors.staff(authentication));
		var name = customers.findNamesByAuthUserIds(List.of(userId)).stream().findFirst().orElse(null);
		return view(saved, name, authentication.getName());
	}

	@PutMapping("/{userId}/role")
	MemberView setRole(@PathVariable UUID userId, @Valid @RequestBody RoleRequest body, Authentication authentication) {
		var saved = staff.changeRole(userId, QueryParams.enumOf(StaffRole.class, body.role(), "role"), Actors.staff(authentication));
		var name = customers.findNamesByAuthUserIds(List.of(userId)).stream().findFirst().orElse(null);
		return view(saved, name, authentication.getName());
	}

	@GetMapping("/invitations")
	List<InvitationView> invitations() {
		var now = clock.instant();
		return staff.invitations().stream().map(i -> new InvitationView(i.id(), i.note(), i.role().name(), i.status(now).name(), i.createdAt(), i.expiresAt())).toList();
	}

	@PostMapping("/invitations")
	@ResponseStatus(HttpStatus.CREATED)
	IssuedView invite(@Valid @RequestBody InviteRequest body, Authentication authentication) {
		var issued = staff.invite(body.note(), QueryParams.enumOf(StaffRole.class, body.role(), "role"), Actors.staff(authentication));
		var i = issued.invitation();
		return new IssuedView(i.id(), i.note(), i.role().name(), i.expiresAt(), issued.code());
	}

	@DeleteMapping("/invitations/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revoke(@PathVariable UUID id, Authentication authentication) {
		staff.revokeInvitation(id, Actors.staff(authentication));
	}

	private static MemberView view(StaffMember m, CustomerJpaRepository.AccountName name, String callerId) {
		var display = name == null ? null : ((name.firstName() == null ? "" : name.firstName()) + " " + (name.lastName() == null ? "" : name.lastName())).strip();
		return new MemberView(m.userId(), m.active(), m.role().name(), m.userId().toString().equals(callerId), m.grantedBy() == null, display == null || display.isEmpty() ? null : display, name == null ? null : name.email(), m.note(),
				m.grantedAt(), m.updatedAt());
	}
}
