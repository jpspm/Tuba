-- Testa as regras do banco como usuário autenticado (RLS ativo).
-- Cada bloco levanta exceção se a regra não se comportar como esperado.

create or replace function pg_temp.login(n int) returns void language sql as $$
  select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000' || n, false)
$$;

-- Dados oficiais -----------------------------------------------------------
do $$
declare total int; improper int; stale int; zones int; with_area int;
begin
  select count(*), count(*) filter (where status = 'impropria'), count(*) filter (where stale),
         count(*) filter (where area is not null)
    into total, improper, stale, with_area from beaches_with_status;
  select count(*) into zones from risk_zones;
  assert total = 27, format('esperava 27 trechos, veio %s', total);
  assert with_area = 27, format('esperava 27 áreas, veio %s', with_area);
  assert improper = 10, format('esperava 10 impróprios no boletim de 09/11, veio %s', improper);
  assert zones = 1, 'esperava 1 zona de risco';
  raise notice 'ok: boletim e áreas (% trechos, % impróprios, % desatualizados)', total, improper, stale;
end $$;

set role authenticated;

-- Report sem posição de quem reporta ----------------------------------------
do $$
begin
  perform pg_temp.login(1);
  insert into reports (type, lat, lng) values ('pollution', -8.12, -34.89);
  raise exception 'FALHOU: aceitou report sem localização';
exception when others then
  assert sqlerrm like 'Ative a localização%', 'erro inesperado: ' || sqlerrm;
  raise notice 'ok: exige localização';
end $$;

-- Report longe de quem reporta ----------------------------------------------
do $$
begin
  perform pg_temp.login(1);
  insert into reports (type, lat, lng, reporter_lat, reporter_lng)
  values ('pollution', -8.12, -34.89, -8.17, -34.91); -- ~6 km
  raise exception 'FALHOU: aceitou report a mais de 2 km';
exception when others then
  assert sqlerrm like 'Você só pode reportar a até 2 km%', 'erro inesperado: ' || sqlerrm;
  raise notice 'ok: bloqueia report distante';
end $$;

-- Report válido: autor, validade e posição descartada --------------------------
do $$
declare r reports;
begin
  perform pg_temp.login(1);
  insert into reports (type, lat, lng, reporter_lat, reporter_lng, user_id, upvotes)
  values ('pollution', -8.12, -34.89, -8.121, -34.891, '00000000-0000-0000-0000-000000000004', 99)
  returning * into r;
  assert r.user_id = '00000000-0000-0000-0000-000000000001', 'autor deveria vir de auth.uid()';
  assert r.upvotes = 0, 'contadores deveriam zerar';
  assert r.reporter_lat is null and r.reporter_lng is null, 'posição de quem reporta não deveria ser salva';
  assert r.expires_at = r.created_at + interval '24 hours', 'poluição deveria valer 24h';
  raise notice 'ok: report válido criado';
end $$;

-- Limite de 5 por hora --------------------------------------------------------
do $$
begin
  perform pg_temp.login(1);
  for i in 1..4 loop
    insert into reports (type, lat, lng, reporter_lat, reporter_lng)
    values ('crowded', -8.12, -34.89, -8.12, -34.89);
  end loop;
  begin
    insert into reports (type, lat, lng, reporter_lat, reporter_lng)
    values ('crowded', -8.12, -34.89, -8.12, -34.89);
    raise exception 'FALHOU: aceitou o 6º report na mesma hora';
  exception when others then
    assert sqlerrm like 'Limite de 5 reports%', 'erro inesperado: ' || sqlerrm;
  end;
  raise notice 'ok: limite por hora';
end $$;

-- Votos ------------------------------------------------------------------------
do $$
declare rid uuid; up int;
begin
  perform pg_temp.login(2);
  select id into rid from reports where type = 'pollution' limit 1;
  insert into report_votes (report_id, user_id, value) values (rid, auth.uid(), 1);
  select upvotes into up from reports where id = rid;
  assert up = 1, format('esperava 1 confirmação, veio %s', up);

  -- trocar o voto (upsert do app)
  insert into report_votes (report_id, user_id, value) values (rid, auth.uid(), -1)
  on conflict (report_id, user_id) do update set value = excluded.value;
  select upvotes into up from reports where id = rid;
  assert up = 0, 'troca de voto deveria recalcular';

  perform pg_temp.login(1);
  begin
    insert into report_votes (report_id, user_id, value) values (rid, auth.uid(), 1);
    raise exception 'FALHOU: autor votou no próprio report';
  exception when others then
    assert sqlerrm like '%row-level security%', 'erro inesperado: ' || sqlerrm;
  end;
  raise notice 'ok: votos';
end $$;

-- Denúncias escondem o report ---------------------------------------------------
do $$
declare rid uuid; visible int;
begin
  perform pg_temp.login(1);
  select id into rid from reports where type = 'pollution' limit 1;
  for n in 2..4 loop
    perform pg_temp.login(n);
    insert into report_flags (report_id, user_id, reason) values (rid, auth.uid(), 'falso');
  end loop;
  select count(*) into visible from active_reports where id = rid;
  assert visible = 0, 'report com 3 denúncias deveria sumir';

  perform pg_temp.login(2);
  begin
    insert into report_flags (report_id, user_id, reason) values (rid, auth.uid(), 'spam');
    raise exception 'FALHOU: denunciou duas vezes';
  exception when unique_violation then null;
  end;
  raise notice 'ok: denúncias';
end $$;

-- Usuário anônimo lê, mas não escreve --------------------------------------------
reset role;
set role anon;
do $$
declare n int;
begin
  perform set_config('request.jwt.claim.sub', '', false);
  select count(*) into n from active_reports;
  assert n > 0, 'anônimo deveria ver os reports';
  begin
    insert into reports (type, lat, lng, reporter_lat, reporter_lng) values ('shark', -8.12, -34.89, -8.12, -34.89);
    raise exception 'FALHOU: anônimo criou report';
  exception when insufficient_privilege then null;
  end;
  raise notice 'ok: anônimo só lê';
end $$;
reset role;
