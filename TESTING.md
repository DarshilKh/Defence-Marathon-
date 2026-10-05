# Testing & Handoff Guide — NTS Race System

This is the document for whoever picks this project up next: how to get it running, how to log in,
how to walk every screen, and — the part that actually matters on race day — how to exercise all
six results-calculation modes and confirm each one produces correct results. It assumes no prior
knowledge of the codebase.

For a feature overview and build/packaging commands, see [README.md](README.md). This document is
about *testing*, start to finish.

---

## 1. What you need before you start

| Purpose | Requirement |
|---|---|
| Run the app at all | Windows PC (the packaged build is Windows-only; RFID DLLs are Windows native) |
| Full UI walkthrough with real tag scans | A CLOU CR15 or Zebra FX RFID reader reachable over the network, plus RFID tags |
| Business-logic testing (no hardware) | Just a JDK — see [§7](#7-testing-without-rfid-hardware-the-automated-suite) |
| Dev run from source | JDK 21, Maven (or the bundled `mvnw`/`mvnw.cmd`), MySQL 8 reachable locally |
| Just testing the finished app | Nothing but Windows — use the packaged `.exe` (no Java/Maven/MySQL install needed) |

**Pick one of two ways to get the app running:**

### Option A — Packaged app (recommended for a tester who isn't a developer)

```
mvnw clean package -Ppackage-exe -DskipTests
```

(Needs a JDK 21 on the *building* machine only, with `JAVA_HOME` set — `jpackage` ships with the
JDK.) This produces `target/dist/AirForceMarathon/` (~185 MB), a fully self-contained folder: a
trimmed Java runtime, the app, the RFID DLLs, and a local SQLite database created on first launch —
**no Java, Maven or MySQL needed on the machine that runs it.** Copy the whole `AirForceMarathon`
folder to a writable location (Desktop, `D:\`, a USB drive — not `C:\Program Files`) and double-click
`AirForceMarathon.exe`.

This is what to hand to a non-technical tester, or install on the race-day laptop itself.

### Option B — Run from source (for developers)

```
mvnw spring-boot:run
```

Needs MySQL reachable using the settings in `src/main/resources/application.properties`
(`database.online.url` / `username` / `password` — the database itself is created automatically if
it doesn't exist). If you don't want to install MySQL just to test, flip one line in that file:

```properties
database.mode=offline
```

and the app uses a local SQLite file (`data/airforce_marathon.db`) instead — zero setup.

> ⚠️ **Before handing this repo to anyone else**, note that `application.properties` currently has a
> real-looking MySQL password committed in plain text (`database.online.password`). Rotate that
> credential and/or move it out of source control (env var, `application-local.properties` that's
> gitignored, etc.) as part of handoff — don't ship it as-is.

---

## 2. First launch

1. **Setup Wizard** — appears automatically on first launch (or via the ⚙️ **Setup** button in the
   top-right of the main window at any time after). It asks for:
   - **Device Role**: `MASTER` (the one control-center PC that runs everything — pick this for all
     normal testing) or `SLAVE` (a field checkpoint device; the sync path to bring its data into
     MASTER is present in the code but its tab is currently disabled in the UI — see [§9](#9-known-gaps--things-not-fully-wired-up)).
   - **Device ID** — free text, e.g. `MASTER`.
   - Reader-related settings for the device.
   - Click **Save & Restart**; the app closes and must be reopened.
2. **Login** — default credentials, auto-created on first run if no users exist:
   - Username: `admin`
   - Password: `admin123`

   (Change this before any real deployment — it's a plain-text default, see `AuthenticationService`.)

---

## 3. The tabs, in the order you'd use them

The main window has 5 tabs (a 6th, Data Sync, is present but disabled — see [§9](#9-known-gaps--things-not-fully-wired-up)):

1. **📝 Registration** — enter runners into the system.
2. **⚙️ Configuration** — define checkpoint locations and their RFID readers.
3. **📋 Race Entries** — pick which registered runners are running in *this* race.
4. **🏁 Live Tracking** — name the race, choose the calculation mode, start/stop the race, watch scans.
5. **📊 Results** — read-only view of whatever Live Tracking last calculated; export to Excel.

### 3.1 Registration — checklist

- [ ] Add a participant manually (name, RFID tag, service number, unit, rank, DOB) and confirm it
      appears in the list.
- [ ] Try registering the **same RFID tag twice** — confirm the app blocks it ("Tag Already
      Registered").
- [ ] **📥 Download Template**, fill a few rows in the generated Excel file, then **Upload Excel** —
      confirm all rows import and the Total count updates.
- [ ] **Export to Excel** and confirm the file opens with the current roster.
- [ ] Edit and delete a participant.

### 3.2 Configuration — checklist

- [ ] Enable **Location 1**, mark it **Start**, give it a name and the IP:Port of a reader (Reader A;
      Reader B is optional for a second reader at the same location).
- [ ] Enable **Location 2** (or reuse Location 4, which defaults to **Finish**) and mark it **Finish**.
- [ ] Click **🔍 Test All** — with real hardware reachable, antenna indicators turn 🟢; unreachable
      readers show 🔴; anything not yet tested/configured stays ⚪.
- [ ] **💾 Save**. Confirm the saved layout re-appears if you leave and come back to the tab.
- [ ] **🔄 Reset** — confirm it clears the checkpoint layout after a confirmation prompt.

Each of the 4 locations supports up to 2 readers (4 antennas each) — up to 16 antennas total across
a race.

### 3.3 Race Entries — checklist

This tab builds a per-race roster: once you save one, live tracking only accepts scans from tags on
this list (everyone else is logged as an "off-roster" scan instead of a result). With no roster
saved, every registered participant is eligible.

- [ ] Select a subset of registered participants (checkboxes) and **Save Roster**.
- [ ] **Download Tag No Template**, fill it with tag numbers, **Upload Tag No Excel** — confirm a
      summary of imported/skipped/not-found rows.
- [ ] **Clear Roster** and confirm it reverts to "all registered participants eligible".

### 3.4 Live Tracking — checklist

- [ ] Enter a **Race Name**, leave/adjust the **duplicate read window** (minutes a repeat scan of the
      same tag at the same checkpoint is ignored — prevents one runner lingering near a reader from
      generating multiple reads), pick a **Reader Type** (CLOU/Zebra), **💾 Save**.
- [ ] Pick a **Calculation Mode** from the dropdown (see [§4](#4-the-six-calculation-modes--exact-test-scripts) below — the extra controls
      that appear change per mode: lap count/distance for Lap Race; in Manual Start mode, a
      ticking clock preview of what **Start Race** is about to declare).
- [ ] **🏁 Start Race** — connects every enabled checkpoint's readers and arms the race, and in
      Manual Start mode also declares that exact moment as the race's Declared Start (the gun
      time), all in one click. The stat cards (Participants / Finished / Active Readers /
      Unregistered scans / Off-roster scans) start updating live as tags are read.
- [ ] Scan tags (or see [§7](#7-testing-without-rfid-hardware-the-automated-suite) if you have no reader) at the configured checkpoints and watch
      rows appear in the live reads table.
- [ ] **⏹️ Stop Race**.
- [ ] **🧮 Calculate Results** — computes results from everything read during the race and switches
      you to the **Results** tab automatically.
- [ ] **🔄 Reset** — confirm it clears the race back to "ready to start" (useful between test runs).
- [ ] **📥 Export Readings** — confirm it dumps the raw tag-read log to Excel.

### 3.5 Results — checklist

- [ ] **Finishers** / **DNF** stat cards match what you'd expect from the data you fed in.
- [ ] **Winner** card shows the fastest finisher's name and time.
- [ ] **Final Results** table: Rank, Name, Tag No, Army Rank, Unit, Category, one column per
      intermediate checkpoint (inserted automatically for modes with midpoints), Total Time, Status —
      confirm sorting/values are correct, and confirm the Rank/Name/Tag No columns render in dark,
      readable text (this was reported as too light and has been fixed in `theme.css`).
- [ ] Running **Manual Start** mode instead shows a *different* table shape (Declared Start / Start
      Time / Finish Time / two duration columns / a Note field) — no Rank column, since that mode
      assigns no rankings (see [§4](#4-the-six-calculation-modes--exact-test-scripts)).
- [ ] **📊 Excel** export — confirm the exported file matches what's on screen.
- [ ] **🔄 Refresh**.

---

## 4. The six calculation modes — exact test scripts

Pick a mode from the **Calculation Mode** dropdown on Live Tracking. Below is what each one needs
configured on the **Configuration** tab, what to scan and in what order, and what to check in
**Results**. These scripts are drawn directly from the app's own regression test suite
(`RaceTimingServiceCalculationModesTest`), so the expected outcomes are exactly what the code is
verified to do.

| # | Dropdown label | Checkpoints needed | Declared start needed? |
|---|---|---|---|
| 1 | 2 CHECKPOINTS - Start + Finish Only | 1 Start + 1 Finish (2 locations) | No — auto, from first scan at Start |
| 2 | SAME START/FINISH - First Read Start, Second Read Finish | 1 location, marked **both** Start and Finish | No |
| 3 | LAP RACE - Same Point, Multiple Laps | 1 location, marked **both** Start and Finish, + a **Lap Count** | No |
| 4 | LONG DISTANCE - Start + Midpoint + Finish | 1 Start + 1 Midpoint (neither Start nor Finish) + 1 Finish (3 locations) | No — auto, from first scan at Start |
| 5 | LONG DISTANCE 2 - Start + 2 Midpoints + Finish | 1 Start + 2 Midpoints + 1 Finish (4 locations) | No — auto |
| 6 | MANUAL START - Declared Start + Start Scan + Finish | 1 Start + 1 **separate** Finish (2 locations, must not be the same point) | **Yes** — declared automatically the moment you click **Start Race** |

### 1. Two Checkpoints (Start + Finish)

1. Configuration: enable 2 locations — one **Start**, one **Finish**.
2. Live Tracking → mode "2 CHECKPOINTS…" → Start Race.
3. Scan Tag A at Start, then at Finish. Scan Tag B at Start only.
4. Calculate Results.
5. **Expect**: Tag A = finisher, net time = Finish − Start, ranked #1. Tag B = **DNF** (started but
   never scanned at Finish).

### 2. Same Start/Finish

1. Configuration: enable 1 location, tick **both** Start and Finish on it.
2. Live Tracking → mode "SAME START/FINISH…" → Start Race.
3. Scan Tag A at that one gate, wait, scan it again at the same gate.
4. Calculate Results.
5. **Expect**: first read = start, second read = finish; net time = second − first.

### 3. Lap Race

1. Configuration: same single gate, both Start and Finish ticked.
2. Live Tracking → mode "LAP RACE…" → a **Lap Count** field appears (also set **Lap Distance (km)**
   if you want distance-aware output) — e.g. set Lap Count = 3.
3. Start Race, then scan Tag A at the gate **3 times** (one per lap) at different timestamps. Scan
   Tag B only **2 times**.
4. Calculate Results.
5. **Expect**: Tag A completes (net time = last read − first read), Tag B is **DNF** (didn't reach
   the configured lap count).

### 4. Long Distance (1 midpoint)

1. Configuration: enable 3 locations — **Start**, a plain **Midpoint** (neither Start nor Finish
   ticked), and **Finish**.
2. Live Tracking → mode "LONG DISTANCE - …Midpoint…" → Start Race (the first scan at the Start
   checkpoint is what stamps the race's start time — there's no manual entry in this mode; if you
   try to Calculate Results with no start time recorded at all, it correctly refuses with a
   "start time not detected" error).
3. Scan Tag A at Start, then Midpoint, then Finish.
4. Calculate Results.
5. **Expect**: Tag A finishes; net time = Finish − (auto-detected) Start; Results shows a Midpoint
   split column.

### 5. Long Distance 2 (2 midpoints)

1. Configuration: enable 4 locations — **Start**, **Midpoint 1**, **Midpoint 2**, **Finish**.
2. Live Tracking → mode "LONG DISTANCE 2 - …2 Midpoints…" → Start Race.
3. Scan Tag A at all four in order. Scan Tag B at Start, Midpoint 1 and Finish — **skip Midpoint 2**.
4. Calculate Results.
5. **Expect**: Tag A finishes with both split columns filled in. Tag B is **DNF even though it has a
   Finish scan** — missing a required midpoint disqualifies the finish. (This is a deliberate,
   tested rule — don't "fix" it if you see it.)

### 6. Manual Start (Declared Start + dual timing)

This is the odd one out — it produces a *differently shaped* results table (no ranks) because it's
built for events with a mass gun-start where you also want each runner's own chip time.

1. Configuration: enable 2 **separate** locations — one **Start**, one **Finish**. (Using the same
   point for both is rejected with a "separate finish checkpoint" error.)
2. Live Tracking → mode "MANUAL START - Declared Start…" → a live-ticking clock previews roughly
   what the declared start is about to be.
3. Click **Start Race** — this both arms the readers AND declares that exact moment as the
   Declared Start (the gun time for everyone), in one click; there's no separate confirm step.
   The clock field freezes to show the captured moment. Scan Tag A at Start a couple of minutes
   after the declared start (simulating a runner crossing the line after the gun), then at Finish
   later. Scan Tag B at Start only (no finish). Scan Tag C at Finish only (no start scan).
4. Calculate Results.
5. **Expect** in the Results tab's *dual-timing* table (Rank column absent — this mode assigns no
   rankings):
   - Tag A: Declared Start = the gun time, Start Time = its own scan, two duration columns (one from
     gun time, one from its own scan) both populated, status Completed.
   - Tag B: has a Start Time, no Finish Time → not completed.
   - Tag C: has a Finish Time and the Declared Start, but no Start Time → not completed.

---

## 5. Excel import/export to test

| Screen | Button | What it does |
|---|---|---|
| Registration | 📥 Download Template / Upload Excel | Bulk-add participants from a spreadsheet |
| Registration | Export to Excel | Dump the current roster |
| Race Entries | Download Tag No Template / Upload Tag No Excel | Bulk-build the per-race roster by tag number |
| Live Tracking | 📥 Export Readings | Dump every raw tag read recorded during the race |
| Results | 📊 Excel | Export the calculated results table |

All of these use Apache POI and always start from **Download Template**, so you never have to guess
column names by hand — generate the template, fill it in, upload it back.

---

## 6. Resetting between test runs

- **Configuration → 🔄 Reset** — clears the checkpoint layout.
- **Race Entries → Clear Roster** — clears the per-race whitelist.
- **Live Tracking → 🔄 Reset** — resets the current race back to "ready to start" so you can re-run a
  mode test without restarting the app.
- **Full wipe (packaged app)**: close the app and delete `app/data/airforce_marathon.db` inside the
  `AirForceMarathon` folder — a clean database is recreated on next launch.
- **Full wipe (dev run, MySQL)**: `DROP DATABASE airforce_marathon;` — it's recreated automatically
  on the next `spring-boot:run` (`createDatabaseIfNotExist=true`).

---

## 7. Testing without RFID hardware — the automated suite

There is no built-in "simulate a tag scan" button in the UI, and the RFID reader clients talk to the
vendor SDKs directly (CLOU's `CLReader` / Zebra's JNI bridge), so a full **UI** walkthrough of a live
race genuinely needs a real reader reachable over the network (or the reader vendor's own desktop
simulator utility, pointed at the IP:port you enter on the Configuration tab — that's outside this
repo).

What you *can* do without any hardware is validate all the calculation logic directly:

```
mvnw test
```

This needs a database reachable the same way a dev run does (MySQL per `application.properties`, or
flip `database.mode=offline` for SQLite — see [§1](#1-what-you-need-before-you-start)). It runs, among others:

- `RaceTimingServiceCalculationModesTest` — one test per calculation mode above, plus the DNF/edge
  cases (missing midpoint, no declared start, same point used for both start and finish, etc.) —
  this is the authoritative spec for what each mode should do.
- `RaceDayFlowTest` — a full register → configure → run → calculate flow.
- `RaceEntryRosterTest` — the Race Entries whitelist behavior.
- `ResultsRenderingTest` — how results map onto the Results table.
- `UiFlowSmokeTest` — screen-to-screen navigation sanity checks.

Run a single test class if you only care about one thing:

```
mvnw test -Dtest=RaceTimingServiceCalculationModesTest
```

---

## 8. Troubleshooting

| Symptom | Likely cause |
|---|---|
| App won't start, no window appears | Check `app/logs/startup-diagnostics.log` (packaged build) or console output (dev run) for a native DLL or DB connection failure |
| "Setup Required" and app exits | The Setup Wizard was cancelled before completing — relaunch and finish it |
| Reader shows 🔴 in Configuration's Test All | Reader unreachable at that IP:port, or its previous TCP session hasn't been released yet — Test All retries a few times automatically before failing |
| Login fails with admin/admin123 | A different admin already exists in this database from a previous run — check the `users` table, or wipe the DB (§6) if this is a fresh test env |
| Calculate Results throws an error | Read the message — this app's calculation modes deliberately refuse with a specific reason (missing start time, missing midpoint, same point used for start+finish, etc.) rather than silently producing wrong numbers. See §4 for the exact rule each mode enforces |
| MySQL connection refused on `mvnw test` / `spring-boot:run` | Either start MySQL and match `application.properties`, or set `database.mode=offline` to use SQLite instead |

---

## 9. Known gaps / things not fully wired up

Worth knowing about before you tell someone "test everything":

- **Data Sync tab** (offline USB export/import between a SLAVE checkpoint device and the MASTER) is
  implemented in `CheckpointDataSyncService`/`DataSyncController`, but its tab is currently disabled
  in the running app (`MainViewController`: `dataSyncTab.setText("💾 Data Sync (Coming Soon)")`) —
  don't spend time testing it through the UI; it isn't reachable yet.
- The **login screen** and the app's default admin password are plain-text and meant for a closed,
  single-organization deployment, not public-facing use — flag this if the handoff includes any kind
  of network exposure.
