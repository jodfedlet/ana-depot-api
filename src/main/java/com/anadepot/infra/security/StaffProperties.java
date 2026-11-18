package com.anadepot.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Team access. {@code requireMfa}: staff access needs a session that completed a second factor (Supabase {@code aal2}); a staff member without it is told to enroll or verify and
 * reaches nothing under /api/v1/admin. {@code invitationDays}: how long an invitation code stays valid.
 */
@ConfigurationProperties(prefix = "ana.staff")
public record StaffProperties(@DefaultValue("false") boolean requireMfa, @DefaultValue("7") int invitationDays) {
}
