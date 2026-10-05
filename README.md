# NTS Race System

A desktop RFID race-timing application built with JavaFX and Spring Boot, used to register runners, configure checkpoint RFID readers, track a race live, and calculate final results.

## Features

- **Registration** — register participants with an RFID tag, personal details (name, service number, unit, rank, date of birth), and bulk import/export via Excel.
- **Checkpoint Configuration** — configure up to 4 checkpoint locations, each with 1–2 RFID readers (4 antennas each), and mark which location is the Start/Finish line.
- **Live Tracking** — set the race name and reader settings, choose a results calculation mode, set the official start time, arm readers, and watch tag reads come in live.
- **Results calculation modes** — 2-checkpoint (start/finish), same start/finish, lap race, long distance (start + 1 or 2 midpoints + finish).
- **RFID reader support** — CLOU (CR15) readers over TCP, and Zebra FX readers via the Zebra RFID3 Java SDK (native JNI bridge).

## Tech stack

- Java 21, Spring Boot 3.5, JavaFX 21
- MySQL (via Spring Data JPA / Hibernate)
- Apache POI for Excel import/export

## Running the app

```
./mvnw spring-boot:run
```

This launches the JavaFX UI inside the Spring Boot application context. A MySQL instance must be reachable using the connection settings in `src/main/resources/application.properties` (the database is created automatically if it doesn't exist).

Zebra reader support requires `RFIDAPI3_JNI_HOST.dll` and `RFIDAPI32PC.dll` to be present in `lib/` (already bundled in this repo).

## Building

```
./mvnw clean package
```

## Standalone Windows app (no Java, Maven or MySQL on the target PC)

```
mvnw clean package -Ppackage-exe -DskipTests
```

Requires Maven to run on a full JDK 21 (set `JAVA_HOME`; `jpackage` ships with the JDK). Output:

```
target/dist/AirForceMarathon/AirForceMarathon.exe
```

That folder (about 185 MB) is fully self-contained: a trimmed Java runtime, all jars, `lib/` with the RFID DLLs, and a local SQLite database created on first launch. Copy the whole `AirForceMarathon` folder anywhere **writable** (Desktop, `D:\`, ...; not `C:\Program Files`) and run the `.exe`.

- The database (`app/data/airforce_marathon.db`), logs, exports and backups live inside `app/` next to `lib/`. Back up the `app/data` folder to keep race data.
- The packaged build reads `app/config/application.properties` (source: `src/main/packaging/config/`), which uses SQLite. The MySQL-based `src/main/resources/application.properties` is used only for dev runs and is left out of the packaged jar.
- On the first launch the Setup Wizard appears; after "Save & Restart" the app closes and needs to be opened again.
- Startup diagnostics (native DLL checks, database mode) are written to `app/logs/startup-diagnostics.log`.
- This produces an app-image (a folder), because WiX is not installed. To get a `.msi`/`.exe` installer, install WiX 3.x and add `-Djpackage.type=msi`.

## Testing

See **[TESTING.md](TESTING.md)** for the full handoff/testing guide: first-launch setup, default
login, a tab-by-tab checklist, and exact step-by-step test scripts for all six race-results
calculation modes (with and without physical RFID hardware).

## Configuration

The online (MySQL) database password is read from the `DB_PASSWORD` environment variable.
Runtime data (`data/`, `logs/`, `backups/`, `exports/`) is git-ignored and never committed.
