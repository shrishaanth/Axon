-- A workspace is one API under observation: its current spec, its keys, its limits.
create table workspace (
    id              uuid primary key,
    name            text        not null,
    ingest_key_hash char(64)    not null unique,
    admin_key_hash  char(64)    not null,
    spec_name       text,
    spec_sha256     char(64),
    spec_text       text,
    event_count     bigint      not null default 0,
    max_events      bigint      not null,
    retention_days  int         not null,
    created_at      timestamptz not null default now()
);

-- Sanitised events, kept so that aggregates can be rebuilt (replay) when the spec changes.
create table traffic_event (
    id           bigserial primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    ts           timestamptz not null,
    payload      text        not null
);
create index traffic_event_workspace_id on traffic_event (workspace_id, id);
create index traffic_event_workspace_ts on traffic_event (workspace_id, ts);

-- Additive counters (see Cells in axon-core). key_hash is the md5 of the logical key, because field paths can
-- be longer than a btree index entry allows.
create table agg_cell (
    workspace_id uuid        not null references workspace (id) on delete cascade,
    key_hash     char(32)    not null,
    day          date        not null,
    kind         text        not null,
    operation    text        not null,
    part         text        not null,
    status       int         not null,
    field        text        not null,
    detail       text        not null,
    client       text        not null,
    count        bigint      not null,
    first_ts     timestamptz not null,
    last_ts      timestamptz not null,
    primary key (workspace_id, key_hash)
);
create index agg_cell_workspace_day on agg_cell (workspace_id, day);
