-- Áreas dos trechos, zonas de risco, dado oficial desatualizado,
-- reports só perto de quem reporta, limite por hora e denúncias.

-- Trechos de praia como polígonos ---------------------------------------

alter table beaches add column reference text;
alter table beaches add column area jsonb; -- GeoJSON Polygon gerado por tools/build_areas.py

alter table official_readings add column source text not null default 'CPRH';

-- Boletim com mais de 12 dias desde a coleta é tratado como desatualizado no app.
drop view beaches_with_status;
create view beaches_with_status with (security_invoker = true) as
select b.id, b.name, b.city, b.reference, b.lat, b.lng, b.area,
       r.status, r.sampled_on, r.source, r.bulletin_url,
       coalesce(r.sampled_on < current_date - 12, true) as stale
from beaches b
left join lateral (
  select status, sampled_on, source, bulletin_url
  from official_readings
  where beach_id = b.id
  order by sampled_on desc
  limit 1
) r on true;

create table risk_zones (
  id          text primary key,
  kind        text not null check (kind in ('shark')),
  name        text not null,
  description text not null,
  source      text not null,
  area        jsonb not null
);

alter table risk_zones enable row level security;
create policy "zonas de risco são públicas" on risk_zones for select using (true);

-- Distância em metros (haversine), suficiente para checar proximidade.
create function distance_m(lat1 double precision, lng1 double precision,
                           lat2 double precision, lng2 double precision)
returns double precision
language sql immutable as $$
  select 2 * 6371000 * asin(sqrt(
    power(sin(radians(lat2 - lat1) / 2), 2) +
    cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lng2 - lng1) / 2), 2)
  ))
$$;

-- Reports: proximidade e limite por hora ---------------------------------

-- O app envia a posição de quem reporta. Ela é usada só na checagem e não fica salva.
alter table reports add column reporter_lat double precision;
alter table reports add column reporter_lng double precision;
alter table reports add column flags int not null default 0;

create or replace function reports_before_insert() returns trigger
language plpgsql as $$
begin
  if new.reporter_lat is null or new.reporter_lng is null then
    raise exception 'Ative a localização para reportar' using errcode = 'P0001';
  end if;
  if distance_m(new.lat, new.lng, new.reporter_lat, new.reporter_lng) > 2000 then
    raise exception 'Você só pode reportar a até 2 km de onde está' using errcode = 'P0001';
  end if;
  if (select count(*) from reports
      where user_id = auth.uid() and created_at > now() - interval '1 hour') >= 5 then
    raise exception 'Limite de 5 reports por hora atingido. Tente mais tarde' using errcode = 'P0001';
  end if;

  new.user_id      := auth.uid();
  new.created_at   := now();
  new.expires_at   := now() + report_lifetime(new.type);
  new.upvotes      := 0;
  new.downvotes    := 0;
  new.flags        := 0;
  new.reporter_lat := null;
  new.reporter_lng := null;
  return new;
end
$$;

-- Denúncias --------------------------------------------------------------

create table report_flags (
  report_id  uuid not null references reports (id) on delete cascade,
  user_id    uuid not null default auth.uid() references auth.users (id) on delete cascade,
  reason     text not null check (reason in ('falso', 'ofensivo', 'spam')),
  created_at timestamptz not null default now(),
  primary key (report_id, user_id)
);

alter table report_flags enable row level security;
create policy "usuário vê as próprias denúncias" on report_flags for select to authenticated
  using (user_id = auth.uid());
create policy "usuário denuncia report alheio" on report_flags for insert to authenticated
  with check (
    user_id = auth.uid()
    and not exists (select 1 from reports r where r.id = report_id and r.user_id = auth.uid())
  );

create function report_flags_after_insert() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  update reports set flags = (select count(*) from report_flags f where f.report_id = new.report_id)
  where id = new.report_id;
  return null;
end
$$;

create trigger report_flags_after_insert
after insert on report_flags
for each row execute function report_flags_after_insert();

-- Com 3 denúncias o report sai do mapa até alguém revisar.
create or replace view active_reports with (security_invoker = true) as
select id, user_id, type, lat, lng, description, created_at, expires_at, upvotes, downvotes
from reports
where expires_at > now()
  and not (downvotes >= 3 and downvotes > upvotes)
  and flags < 3;
