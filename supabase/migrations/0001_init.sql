-- Tuba: balneabilidade oficial (CPRH) + reports da comunidade.

create type official_status as enum ('propria', 'impropria', 'sem_dados');

create type report_type as enum (
  'good_surf', 'good_swim', 'rough_sea', 'pollution', 'sewage',
  'shark', 'jellyfish', 'other_danger', 'crowded'
);

-- Pontos de monitoramento -------------------------------------------------

create table beaches (
  id   text primary key,            -- identificador estável do ponto (ex.: código da CPRH)
  name text not null,
  city text not null,
  lat  double precision not null,
  lng  double precision not null
);

create table official_readings (
  id           bigint generated always as identity primary key,
  beach_id     text not null references beaches (id) on delete cascade,
  status       official_status not null,
  sampled_on   date not null,
  bulletin_url text,
  created_at   timestamptz not null default now(),
  unique (beach_id, sampled_on)
);

create view beaches_with_status with (security_invoker = true) as
select b.id, b.name, b.city, b.lat, b.lng, r.status, r.sampled_on, r.bulletin_url
from beaches b
left join lateral (
  select status, sampled_on, bulletin_url
  from official_readings
  where beach_id = b.id
  order by sampled_on desc
  limit 1
) r on true;

-- Reports da comunidade ---------------------------------------------------

-- Precisa bater com ReportType.lifetimeHours no app.
create function report_lifetime(t report_type) returns interval
language sql immutable as $$
  select case t
    when 'good_surf'    then interval '4 hours'
    when 'good_swim'    then interval '4 hours'
    when 'rough_sea'    then interval '4 hours'
    when 'pollution'    then interval '24 hours'
    when 'sewage'       then interval '48 hours'
    when 'shark'        then interval '12 hours'
    when 'jellyfish'    then interval '12 hours'
    when 'other_danger' then interval '12 hours'
    when 'crowded'      then interval '3 hours'
  end
$$;

create table reports (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  type        report_type not null,
  lat         double precision not null check (lat between -90 and 90),
  lng         double precision not null check (lng between -180 and 180),
  description text check (char_length(description) <= 280),
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,
  upvotes     int not null default 0,
  downvotes   int not null default 0
);

create index reports_active_idx on reports (expires_at, lat, lng);

-- O cliente não escolhe autor, datas nem contadores.
create function reports_before_insert() returns trigger
language plpgsql as $$
begin
  new.user_id    := auth.uid();
  new.created_at := now();
  new.expires_at := now() + report_lifetime(new.type);
  new.upvotes    := 0;
  new.downvotes  := 0;
  return new;
end
$$;

create trigger reports_before_insert
before insert on reports
for each row execute function reports_before_insert();

create table report_votes (
  report_id  uuid not null references reports (id) on delete cascade,
  user_id    uuid not null default auth.uid() references auth.users (id) on delete cascade,
  value      smallint not null check (value in (-1, 1)),
  created_at timestamptz not null default now(),
  primary key (report_id, user_id)
);

-- Recalcula contadores; confirmar estende a validade em metade do tempo de vida do tipo.
create function report_votes_after_change() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  rid uuid := coalesce(new.report_id, old.report_id);
begin
  update reports r set
    upvotes   = (select count(*) from report_votes v where v.report_id = rid and v.value = 1),
    downvotes = (select count(*) from report_votes v where v.report_id = rid and v.value = -1),
    expires_at = case
      when tg_op <> 'DELETE' and new.value = 1
        then greatest(r.expires_at, now() + report_lifetime(r.type) / 2)
      else r.expires_at
    end
  where r.id = rid;
  return null;
end
$$;

create trigger report_votes_after_change
after insert or update or delete on report_votes
for each row execute function report_votes_after_change();

-- Visível: não expirou e não foi derrubado por negações.
create view active_reports with (security_invoker = true) as
select *
from reports
where expires_at > now()
  and not (downvotes >= 3 and downvotes > upvotes);

-- RLS ---------------------------------------------------------------------

alter table beaches enable row level security;
alter table official_readings enable row level security;
alter table reports enable row level security;
alter table report_votes enable row level security;

create policy "beaches são públicas" on beaches for select using (true);
create policy "leituras são públicas" on official_readings for select using (true);

create policy "reports são públicos" on reports for select using (true);
create policy "usuário cria report" on reports for insert to authenticated
  with check (user_id = auth.uid());
create policy "usuário apaga o próprio report" on reports for delete to authenticated
  using (user_id = auth.uid());

create policy "votos são públicos" on report_votes for select using (true);
create policy "usuário vota em report alheio" on report_votes for insert to authenticated
  with check (
    user_id = auth.uid()
    and not exists (select 1 from reports r where r.id = report_id and r.user_id = auth.uid())
  );
create policy "usuário muda o próprio voto" on report_votes for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "usuário remove o próprio voto" on report_votes for delete to authenticated
  using (user_id = auth.uid());
