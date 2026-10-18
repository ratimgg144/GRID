-- GRID milestone 1: manually apply to a new Supabase project.
-- No public access to route points. All accepted leaderboard distance is recomputed by SECURITY DEFINER.
create extension if not exists pgcrypto;

create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    display_name text not null check (char_length(display_name) between 2 and 40),
    ranking_opt_in boolean not null default false,
    city text,
    created_at timestamptz not null default now()
);
create table if not exists public.trip_records (
    id uuid primary key,
    owner_id uuid not null references auth.users(id) on delete cascade,
    started_at timestamptz not null,
    ended_at timestamptz not null,
    accepted_meters integer not null default 0 check (accepted_meters >= 0),
    status text not null check(status in ('accepted','rejected')),
    rejection_reason text,
    created_at timestamptz not null default now(),
    constraint valid_dates check (ended_at >= started_at)
);
create index if not exists idx_trip_records_period on public.trip_records(status, started_at, owner_id);
create table if not exists public.private_trip_points (
    trip_id uuid not null references public.trip_records(id) on delete cascade,
    ordinal integer not null,
    recorded_at timestamptz not null,
    latitude double precision not null check(latitude between -90 and 90),
    longitude double precision not null check(longitude between -180 and 180),
    accuracy_m real not null check(accuracy_m > 0),
    primary key (trip_id, ordinal)
);

alter table public.profiles enable row level security;
alter table public.trip_records enable row level security;
alter table public.private_trip_points enable row level security;
revoke all on public.profiles, public.trip_records, public.private_trip_points from anon, authenticated;
grant select, update on public.profiles to authenticated;
grant select, delete on public.trip_records to authenticated;
grant select, delete on public.private_trip_points to authenticated;
create policy "read own profile" on public.profiles for select to authenticated using (id = (select auth.uid()));
create policy "edit own profile" on public.profiles for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));
create policy "read own trips" on public.trip_records for select to authenticated using (owner_id = (select auth.uid()));
create policy "delete own trips" on public.trip_records for delete to authenticated using (owner_id = (select auth.uid()));
create policy "read own points" on public.private_trip_points for select to authenticated using (
    exists (select 1 from public.trip_records t where t.id = trip_id and t.owner_id = (select auth.uid()))
);
create policy "delete own points" on public.private_trip_points for delete to authenticated using (
    exists (select 1 from public.trip_records t where t.id = trip_id and t.owner_id = (select auth.uid()))
);

create or replace function public.create_profile_for_signup() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.profiles(id, display_name) values(new.id, 'Driver-' || substring(new.id::text from 1 for 6)) on conflict do nothing;
    return new;
end;
$$;
drop trigger if exists create_grid_profile on auth.users;
create trigger create_grid_profile after insert on auth.users for each row execute function public.create_profile_for_signup();

-- For already existing auth users in the project.
insert into public.profiles(id, display_name)
select id, 'Driver-' || substring(id::text from 1 for 6) from auth.users on conflict do nothing;

create or replace function public.submit_trip(
    p_trip_id uuid, p_started_ms bigint, p_ended_ms bigint, p_samples jsonb
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
    v_existing public.trip_records%rowtype;
    v_start timestamptz;
    v_end timestamptz;
    v_sample jsonb;
    v_count integer;
    v_index integer := 0;
    v_lat double precision;
    v_lon double precision;
    v_time bigint;
    v_accuracy double precision;
    v_last_lat double precision;
    v_last_lon double precision;
    v_last_time bigint;
    v_last_accuracy double precision;
    v_distance double precision := 0;
    v_total double precision := 0;
    v_a double precision;
    v_dlat double precision;
    v_dlon double precision;
    v_dt double precision;
    v_bad integer := 0;
    v_good integer := 0;
    v_status text;
    v_reason text;
begin
    if v_user is null then raise exception 'Sign in required' using errcode = '28000'; end if;
    select * into v_existing from public.trip_records where id = p_trip_id;
    if found then
        if v_existing.owner_id <> v_user then raise exception 'Trip id belongs to another account'; end if;
        return jsonb_build_object('status',v_existing.status,'accepted_meters',v_existing.accepted_meters,'duplicate',true);
    end if;
    if p_started_ms is null or p_ended_ms is null or p_started_ms >= p_ended_ms
        or p_started_ms < 1577836800000 or p_ended_ms > (extract(epoch from clock_timestamp()) * 1000)::bigint + 300000
        or p_ended_ms - p_started_ms > 86400000 then
        raise exception 'Invalid trip date or duration';
    end if;
    if jsonb_typeof(p_samples) is distinct from 'array' then raise exception 'Points must be array'; end if;
    v_count := jsonb_array_length(p_samples);
    if v_count > 10000 then raise exception 'Too many GPS fixes'; end if;
    v_start := to_timestamp(p_started_ms / 1000.0);
    v_end := to_timestamp(p_ended_ms / 1000.0);
    for v_sample in select value from jsonb_array_elements(p_samples) loop
        v_index := v_index + 1;
        begin
            v_lat := (v_sample->>'lat')::double precision;
            v_lon := (v_sample->>'lon')::double precision;
            v_time := (v_sample->>'t')::bigint;
            v_accuracy := (v_sample->>'accuracy')::double precision;
        exception when others then raise exception 'Invalid GPS fix'; end;
        if v_lat is null or v_lon is null or v_accuracy is null or v_time is null
            or not (v_lat between -90 and 90 and v_lon between -180 and 180 and v_accuracy between 0.1 and 35)
            or v_time < p_started_ms - 15000 or v_time > p_ended_ms + 15000 then
            v_bad := v_bad + 1; continue;
        end if;
        if v_last_time is not null then
            if v_time <= v_last_time then v_bad := v_bad + 1; continue; end if;
            v_dt := (v_time - v_last_time) / 1000.0;
            if v_dt between 0.8 and 120 then
                v_dlat := radians(v_lat - v_last_lat);
                v_dlon := radians(v_lon - v_last_lon);
                v_a := power(sin(v_dlat / 2), 2) + cos(radians(v_last_lat)) * cos(radians(v_lat)) * power(sin(v_dlon / 2), 2);
                v_distance := 12742000.0 * asin(sqrt(greatest(0.0,least(1.0,v_a))));
                if v_distance > greatest(8.0,(v_accuracy + v_last_accuracy) * 1.5) and v_distance / v_dt between 1.3 and 65.0 then
                    v_total := v_total + v_distance;
                elsif v_distance / v_dt > 65.0 then
                    v_bad := v_bad + 1;
                    -- Do not reward the jump; treat current point as the next anchor.
                end if;
            end if;
        end if;
        v_good := v_good + 1;
        v_last_lat := v_lat; v_last_lon := v_lon; v_last_time := v_time; v_last_accuracy := v_accuracy;
    end loop;
    if v_good < 10 then v_reason := 'not_enough_fixes';
    elsif p_ended_ms - p_started_ms < 120000 then v_reason := 'too_short';
    elsif v_total < 500 then v_reason := 'distance_below_500m';
    elsif v_bad > greatest(5, v_good / 10) then v_reason := 'gps_anomalies';
    else v_reason := null; end if;
    v_status := case when v_reason is null then 'accepted' else 'rejected' end;
    insert into public.trip_records(id,owner_id,started_at,ended_at,accepted_meters,status,rejection_reason)
    values(p_trip_id,v_user,v_start,v_end,case when v_reason is null then round(v_total)::integer else 0 end,v_status,v_reason)
    on conflict (id) do nothing;
    -- Only the creator's accepted points are stored. They remain RLS-private.
    if (select owner_id from public.trip_records where id = p_trip_id) = v_user then
        insert into public.private_trip_points(trip_id,ordinal,recorded_at,latitude,longitude,accuracy_m)
        select p_trip_id, e.ordinality::integer, to_timestamp((e.value->>'t')::bigint / 1000.0),
               (e.value->>'lat')::double precision, (e.value->>'lon')::double precision, (e.value->>'accuracy')::real
        from jsonb_array_elements(p_samples) with ordinality as e(value, ordinality)
        where (e.value->>'t') is not null
          and (e.value->>'lat') is not null and (e.value->>'lon') is not null and (e.value->>'accuracy') is not null
          and (e.value->>'lat')::double precision between -90 and 90
          and (e.value->>'lon')::double precision between -180 and 180
          and (e.value->>'accuracy')::double precision between 0.1 and 35
        on conflict do nothing;
    end if;
    select * into v_existing from public.trip_records where id = p_trip_id;
    if v_existing.owner_id <> v_user then raise exception 'Trip id conflict'; end if;
    return jsonb_build_object('status',v_existing.status,'accepted_meters',v_existing.accepted_meters,
                              'reason',v_existing.rejection_reason);
end;
$$;

create or replace function public.distance_leaderboard(
    p_period text default 'week', p_limit integer default 50, p_offset integer default 0
) returns table("rank" bigint, display_name text, km numeric)
language plpgsql security definer set search_path = '' as $$
declare v_from timestamptz;
begin
    if auth.uid() is null then raise exception 'Sign in required'; end if;
    if p_period = 'week' then
        v_from := date_trunc('week', clock_timestamp() at time zone 'Asia/Tbilisi') at time zone 'Asia/Tbilisi';
    elsif p_period = 'month' then
        v_from := date_trunc('month', clock_timestamp() at time zone 'Asia/Tbilisi') at time zone 'Asia/Tbilisi';
    elsif p_period = 'all' then
        v_from := '2020-01-01'::timestamptz;
    else raise exception 'Invalid period'; end if;
    return query
    with totals as (
        select p.id, p.display_name, sum(t.accepted_meters)::bigint as meters
        from public.trip_records t join public.profiles p on p.id = t.owner_id
        where t.status = 'accepted' and t.started_at >= v_from and p.ranking_opt_in
        group by p.id, p.display_name
    ), placed as (
        select dense_rank() over(order by meters desc) as r, id, display_name as dn, meters from totals
    )
    select r, dn, round(meters / 1000.0, 2) from placed
    order by r, id limit least(greatest(p_limit,1), 100) offset least(greatest(p_offset,0), 10000);
end;
$$;

revoke execute on function public.submit_trip(uuid,bigint,bigint,jsonb) from public,anon;
revoke execute on function public.distance_leaderboard(text,integer,integer) from public,anon;
grant execute on function public.submit_trip(uuid,bigint,bigint,jsonb) to authenticated;
grant execute on function public.distance_leaderboard(text,integer,integer) to authenticated;

-- Optional car photos must not be public by default.
insert into storage.buckets(id,name,public) values('grid-car-photos','grid-car-photos',false) on conflict (id) do nothing;
create policy "grid users read own car photos" on storage.objects for select to authenticated
using (bucket_id = 'grid-car-photos' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "grid users upload own car photos" on storage.objects for insert to authenticated
with check (bucket_id = 'grid-car-photos' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "grid users delete own car photos" on storage.objects for delete to authenticated
using (bucket_id = 'grid-car-photos' and (storage.foldername(name))[1] = (select auth.uid())::text);
