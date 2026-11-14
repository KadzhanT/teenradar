create table terms (
    id            bigserial primary key,
    slug          text        not null unique,
    term          text        not null,
    meaning       text        not null,
    risk          text        not null check (risk in ('GREEN', 'YELLOW', 'RED')),
    why           text,
    how_to_talk   text,
    -- KNOWN = curated reference code, SEED = starter slang, INGEST = found by daily job, DECODE = first seen in a parent's query
    source        text        not null check (source in ('KNOWN', 'SEED', 'INGEST', 'DECODE')),
    verified      boolean     not null default false,
    -- KNOWN codes only: also flag it when it appears in a message even if the AI missed it (only for unambiguous codes)
    match_in_text boolean     not null default false,
    created_at    timestamptz not null default now()
);

create table term_hits (
    term_id    bigint not null references terms (id) on delete cascade,
    week_start date   not null,
    hits       int    not null default 0,
    primary key (term_id, week_start)
);

create table visitors (
    id           uuid primary key,
    premium      boolean     not null default false,
    decode_day   date,
    decode_count int         not null default 0,
    created_at   timestamptz not null default now()
);

create table subscribers (
    id         bigserial primary key,
    email      text        not null unique,
    created_at timestamptz not null default now()
);
