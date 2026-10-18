# GRID — Android car garage + phone GPS recorder

Native Kotlin / Jetpack Compose / Room. `ge.grid.app`. V0.1.0 local-first, deliberately not presented as production ready.

## In this source drop

- Real local garage: multiple cars, owner-supplied specs, local car photograph via Android document picker.
- Export all on-device cars and precise trip points to user-selected JSON, or erase all local cars and trips (with confirmation).
- On-device recording with Android `location` foreground service and persistent notification; start, pause, resume, finish, GPS accuracy indicator, live GPS speed when a recent usable fix exists, stored route/summary.
- Offline persistence via Room, service restart attempt (`START_STICKY`), explicitly does NOT auto-launch from a background activity.
- Filtering for mock signals, inaccurate fixes, stationary noise, implausible jumps, long gaps, out-of-order points.
- Five navigation areas, with Clubs explicitly unavailable rather than fake community data.
- Native MapLibre route map **only if** a MapTiler key is configured; otherwise an **offline geometric route trace** (not a street map). Attribution shown; check current MapTiler terms/quotas before scale.
- Optional Supabase email/password auth + email recovery + idempotent server trip submissions + opt-in aggregated rankings for this milestone, pending backend setup.
- Server migration includes RLS on private routes, security-definer ingestion with GPS plausibility checks, per-user privacy and aggregate rankings (Asia/Tbilisi).
- GitHub Actions: test, lint, build debug APK; signed tag release with secrets.

## Important limits: do not infer functionality that isn't here

1. No verified device test or APK yet. Local-only JVM GPS calculation smoke tests passed; Android build is unverified until CI runs.
2. Clubs, follows, social posting/report/block, photo cloud upload, deletion of cloud account, privacy-zone route export, achievements, monthly recap, share cards and full account recovery app links are **not implemented**. This is a first vertical slice, not the final requested release.
3. Local photos are kept as persisted user-selected URI grants, not silently uploaded.
4. Backend only works **after** you configure and migrate a dedicated Supabase project. Social stats NEVER use fake data. The backend treats GPS readings as *untrusted*: it recalculates plausible distance but cannot prove the recording occurred in the claimed vehicle and cannot prevent coordinated GPS spoofing.
5. This build intentionally records no public max-speed data; only private GPS max speed. No Track Mode or public high-speed leaderboards.
6. A finished trip becomes eligible for rankings if 500+ server-accepted metres, 2+ minutes and at least 10 valid fixes, with an active opt-in profile. Rejected trips remain local and are marked as rejected when sync returns.
7. Session access token only retained in app memory; signing in again may be required after process death.
8. Trip deletion in this milestone deletes **local** data only. If you synced, delete the server copy directly from Supabase Dashboard until account management is implemented. Never assume local deletion deletes cloud data.
9. No background location permission is requested; start from a visible activity; ongoing foreground service records while screen is off. Vendor battery killing, force-stop, reboot, permissions revocation and notification restrictions are physical-device test risks.

## How to build APK without Android Studio

Create a new **empty** repository `GRID` under your GitHub account, not `KODA`. Upload the **contents** of this project folder at the repository root (not a single ZIP). Ensure Actions are enabled. Open Actions -> Android CI and APK -> Run workflow. The `debug` job should produce an artifact `GRID-debug-<sha>` containing `app-debug.apk`. No backend key is required to build or use offline garage/trips.

The runner uses Gradle 8.13 directly via `gradle/actions/setup-gradle@v6`, so a Gradle Wrapper binary is not necessary. To build on your computer, install JDK 17, Android SDK 35 + Build Tools 35, Gradle 8.13, then run `gradle :app:testDebugUnitTest :app:assembleDebug`.

## Enable actual backend-ranked trip sync (optional after offline APK works)

1. Create separate **development** and **production** Supabase projects.
2. In each SQL Editor, run `supabase/migrations/202610180001_grid_m1.sql`. Enable email/password sign-in and review email confirmation/redirect configuration.
3. GitHub Settings -> Secrets and variables -> Actions -> **Variables**: `SUPABASE_URL`, `SUPABASE_ANON_KEY`. These are publishable client-side values. **Never use the service-role secret** in your APK, GitHub repo or public Actions variables. For local builds copy `local.properties.example` as `local.properties` and add your values.
4. Sign up in the APK, verify email if required, sign in. In Profile, opt into rankings. Record a real journey, finish and select Sync on the saved trip page. Refresh Rankings.
5. Development/prod separation currently via build-time values; use distinct workflows/environments and package IDs before shipping parallel installable flavours.

Server defines week as Monday 00:00 Asia/Tbilisi and month as local first day. Ties use whole accepted metres. Data is never described as odometer mileage.

## Maps

Optional Actions variable `MAPTILER_KEY`, or `MAPTILER_KEY=` in local.properties, enables native MapLibre and MapTiler vector basemap. MapTiler requires a separate scoped usage key and attribution; read plan rate limits, map licensing and API key protection. Without the key, only an offline route trace (not an actual basemap) is shown. No use of public OSM tile servers is assumed.

## Signing and version tags

Use `scripts/create-signing-key.ps1` on Windows (or `scripts/create-signing-key.sh` on Linux) on your own trusted computer; keep the `.jks` file and passphrases backed up in **multiple secure places**. In repository Secrets add:

- `GRID_KEYSTORE_BASE64` — full Base64 of the `.jks` file, with no wrapping/newlines.
- `GRID_KEYSTORE_PASSWORD`, `GRID_KEY_ALIAS`, `GRID_KEY_PASSWORD`.

Push a tag `v0.1.0`; the workflow builds and publishes the **signed** APK under GitHub Releases. If secrets are missing, release job fails rather than calling an unsigned APK signed. GitHub hosts only code and APKs; Supabase is separately hosted and billed. Changing or losing release keystore breaks updates for installs signed with the original certificate. Increment `versionCode` for each future release.

## Acceptance checks still due

Android compile; emulator add/save/restore flow; notification pause/resume/finish; process kill; OS background limits; GPS dropout/drift; device battery restrictions; locale rendering; MapTiler license and attribution; auth flow; SQL migration apply; idempotency under concurrent retries; RLS attempts as different users; timezone week boundary; 1000+ point trips; accessibility and contrast.

**No APK/repository URL is claimed until GitHub CI has actually completed successfully.**
