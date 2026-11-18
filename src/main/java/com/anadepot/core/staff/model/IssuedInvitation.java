package com.anadepot.core.staff.model;

/** The invitation plus the one-time code. This is the ONLY moment the code exists in clear: it is shown to the inviter once and never retrievable again. */
public record IssuedInvitation(StaffInvitation invitation, String code) {

	@Override
	public String toString() {
		return "IssuedInvitation[code=hidden]";
	}
}
