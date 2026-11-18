-- Staff management without a database administrator. A store member invites a colleague with a ONE-TIME CODE that is shown once and stored only as a
-- SHA-256 hash: nobody (not even a database reader) can recover it. The invitee, signed in with their own account, redeems it and becomes staff.
-- Nothing here trusts an e-mail address typed by a user, and the backend never needs the identity provider's service-role key.
-- Membership stays all-or-nothing: roles/permissions remain an open business decision (docs/decisions.md).

create table staff_invitations (
    id           uuid        primary key,
    code_hash    varchar(64) not null unique check (length(code_hash) = 64),
    note         varchar(80) not null,          -- who the invitation is for, chosen by the inviter ("Marie, cash desk"); shown in the team list
    created_by   uuid        not null,
    created_at   timestamptz not null,
    expires_at   timestamptz not null,
    accepted_by  uuid,
    accepted_at  timestamptz,
    revoked_at   timestamptz,
    revoked_by   uuid,
    check (expires_at > created_at),
    check ((accepted_at is null) = (accepted_by is null)),
    check (accepted_at is null or revoked_at is null)
);
create index staff_invitations_open_idx on staff_invitations (expires_at) where accepted_at is null and revoked_at is null;
alter table staff_invitations enable row level security;

-- Who deactivated or reactivated a member, and when (the audit trail also records it).
alter table staff_members add column updated_by uuid;
alter table staff_members add column updated_at timestamptz;
