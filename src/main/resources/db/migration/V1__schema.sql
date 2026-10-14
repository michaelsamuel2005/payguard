-- payguard schema, version 1.
-- The application applies this with Flyway; the JDBC tests apply the same file to a fresh schema.

create table payees (
    id               uuid        primary key,
    customer_id      text        not null check (length(customer_id) between 1 and 64),
    name             text        not null check (length(name) between 1 and 70),
    sort_code        char(6)     not null check (sort_code ~ '^[0-9]{6}$'),
    account_number   char(8)     not null check (account_number ~ '^[0-9]{8}$'),
    name_check       text        not null check (name_check in ('MATCH', 'CLOSE_MATCH', 'NO_MATCH', 'UNAVAILABLE')),
    close_match_name text,
    created_at       timestamptz not null,
    constraint payees_one_per_account unique (customer_id, sort_code, account_number),
    -- only a close match records the account holder's name (see NameCheckResult)
    constraint payees_close_match_name check ((name_check = 'CLOSE_MATCH') = (close_match_name is not null))
);

create table payments (
    id           uuid        primary key,
    customer_id  text        not null,
    payee_id     uuid        not null references payees (id),
    amount_pence bigint      not null check (amount_pence > 0),
    reference    text        not null check (length(reference) between 1 and 18),
    status       text        not null check (status in ('RELEASED', 'AWAITING_CONFIRMATION', 'HELD', 'REJECTED', 'CANCELLED')),
    outcome      text        not null check (outcome in ('ALLOW', 'WARN', 'HOLD')),
    risk_score   integer     not null check (risk_score >= 0),
    signals      text[]      not null,
    warning_code text,
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    version      integer     not null default 0,
    constraint payments_warning_unless_allowed check ((outcome = 'ALLOW') = (warning_code is null))
);

create index payments_by_customer on payments (customer_id, created_at desc);
create index payments_released_by_payee on payments (customer_id, payee_id) where status = 'RELEASED';
create index payments_review_queue on payments (created_at, id) where status = 'HELD';

create table idempotency_keys (
    customer_id     text        not null,
    idempotency_key text        not null check (length(idempotency_key) between 8 and 64),
    request_hash    char(64)    not null,
    payment_id      uuid        not null references payments (id),
    created_at      timestamptz not null,
    primary key (customer_id, idempotency_key)
);

create table audit_events (
    id          bigint      generated always as identity primary key,
    payment_id  uuid        not null references payments (id),
    actor       text        not null,
    action      text        not null,
    from_status text,
    to_status   text        not null,
    detail      text        not null,
    at          timestamptz not null
);

create index audit_events_by_payment on audit_events (payment_id, id);

-- The audit trail is append-only: once written, no row may be changed or removed.
create function audit_events_append_only() returns trigger
    language plpgsql as
$$
begin
    raise exception 'audit_events is append-only';
end;
$$;

create trigger audit_events_no_update_or_delete
    before update or delete on audit_events
    for each row execute function audit_events_append_only();

create trigger audit_events_no_truncate
    before truncate on audit_events
    for each statement execute function audit_events_append_only();
