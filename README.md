# Smart Parking Garage

Software Design Patterns, Assignment 4. Assigned domain: **Smart Parking Garage, individual variant 20** in `Assignment_4_Facade patterm (2).docx`.

This Java 17 console application confirms a prepaid parking booking through three independent subsystems. A holds an entry clearance, B reserves a usable parking space, and C records payment in a local ledger. A failure after an earlier stage has committed causes explicit, durable compensation. SQLite stores domain records, workflow progress and an ordered event history.

`COMPLETED` means the prepaid booking is confirmed. The gate's `HELD` clearance and the space's `RESERVED` allocation remain available for later admission and occupancy. The standalone demonstrations show those physical-use status transitions. The project models a local garage and its payment ledger; it does not operate physical hardware or contact a bank.

## Build and run on Windows

Open PowerShell in the project folder. Java 17 is required; Maven itself is supplied by the Maven Wrapper.

```powershell
.\mvnw.cmd -q compile exec:java
```

The prepared workspace contains a project-local JDK under `.tools/jdk-17`. On another Windows x64 machine, either configure `JAVA_HOME` for a JDK 17 or run the one-time setup:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup-java.ps1
```

The setup downloads a pinned Eclipse Temurin 17 archive and verifies its SHA-256 before extracting it inside this project. It does not change global environment settings. The wrapper prefers that local JDK. First setup/build needs Internet access to download the JDK, Maven and dependencies; normal application operation is local. After dependencies are cached, Maven can run offline with `-o`.

Build, run all tests, generate coverage and enforce the assignment's threshold:

```powershell
.\mvnw.cmd clean verify
```

After packaging, the application can also run without Maven:

```powershell
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar
```

Use `java` instead of the project-local executable if your JDK is on `PATH`. The default database is `data/parking.db`. Supply `--db` to use a different local file, for example:

```powershell
.\mvnw.cmd -q compile exec:java "-Dexec.args=--db data/exam.db"
```

## Project structure

```text
SDP-Assignment-4/
├── Assignment_4_Facade patterm (2).docx
├── Assignment4_Report.docx
├── README.md
├── DEFENSE_GUIDE.md
├── pom.xml
├── mvnw.cmd
├── .mvn/                         Maven Wrapper and local build configuration
├── scripts/                      Java setup and verification helpers
├── src/main/java/com/smartparking/
│   ├── app/                      ParkingApplication, ParkingConsole, GarageRuntime
│   ├── facade/                   ParkingFacade and AdministrationFacade
│   ├── gate/                     Independent subsystem A, four classes
│   ├── spaces/                   Independent subsystem B, four classes
│   ├── payment/                  Independent subsystem C, four classes
│   ├── workflow/                 Request, progress and event journal
│   └── infrastructure/           SQLite transaction helper and exceptions
├── src/test/java/com/smartparking/
├── docs/                         Requirements, UML and verified evidence
├── data/                         Local SQLite files, excluded from Git
├── .tools/                       Local JDK and build caches, excluded from Git
└── target/                       Generated jar, tests and coverage, excluded from Git
```

## Architecture and Facade

The client uses `ParkingFacade.park(ParkingRequest)` for the common workflow. It does not choose gateway calls, SQL statements, rollback order or recovery steps. `AdministrationFacade` provides inspection, history, recovery, manual cancellation, sensor maintenance and standalone demonstrations. `GarageRuntime` is the composition root: it constructs collaborators and injects them through constructors. The interactive `ParkingConsole` depends on the facades and request/result types.

| Subsystem | Four meaningful classes | Responsibility and lifecycle |
|---|---|---|
| A, `gate` | `GateAdmission`, `LicensePlateReader`, `GateGateway`, `GateController` | Validate plate and vehicle clearance; hold an entry permit. HELD → ADMITTED → CANCELLED; a HELD permit may also be cancelled. |
| B, `spaces` | `ParkingReservation`, `SpaceSensorGrid`, `ReservationGateway`, `ReservationManager` | Validate spaces 1–3 and reject blocked or already allocated spaces. RESERVED → OCCUPIED → RELEASED; a RESERVED allocation may also be released. |
| C, `payment` | `ParkingPayment`, `OverstayAlert`, `PaymentGateway`, `PaymentTerminal` | Calculate 500 cents/hour for 1–24 hours, validate tender, record payment and calculate overdue minutes. AUTHORIZED → CAPTURED → REFUNDED; an authorization may also be reversed. |

Each entity owns legal state transitions and rejects invalid transitions even without a facade. The three pure policy classes have no persistence dependency. Each subsystem gateway talks directly to SQLite through the technical `Database` helper and owns its schema. There are no cross-subsystem imports or calls. Nested status enums do not inflate the four-class count. Shared request/workflow/infrastructure classes are outside the subsystem count.

Responsibilities are separated between entities, rules, gateways, orchestration and input/output (SRP). Constructor injection makes dependencies explicit. Narrow APIs prevent the client from manipulating internals. The design uses concrete collaborators where only one local implementation is needed; it does not claim that constructor injection alone proves the Dependency Inversion Principle. A future hardware or bank integration would justify adding an interface at that boundary. There is no inheritance hierarchy requiring artificial Liskov or Interface Segregation examples.

## Persistence and recovery

SQLite stores gate admissions, reservations, space sensor states, payments, workflows and workflow events. Connections use foreign keys, a busy timeout, WAL journaling and `synchronous=FULL`. `Database.transaction` commits one gateway operation at a time. It never encloses A, B and C in one transaction.

The normal progress is `PENDING_A → PENDING_B → PENDING_C → COMPLETED`. A failure before any domain commit produces `REJECTED`. Later failures first persist `COMPENSATING`, then undo recorded effects in reverse order, then persist `COMPENSATED`. A progress transition and its associated journal event commit together.

A process can stop between a subsystem commit and its progress update. Recovery therefore reloads workflow state and calls idempotent subsystem APIs with the original operation ID. It also inspects persisted entities before compensation. A repeated identical request does not create another charge or reservation; reusing an operation ID with different input is rejected. Existing compensated entities cannot be resurrected by retrying a forward operation.

Compensation commits each reversal separately and retains records. If undoing B fails, A is not cancelled prematurely. The workflow remains `COMPENSATING`; administrative recovery retries from persisted entity states. Event keys suppress duplicate history entries during retries. For a manual cancellation of a completed booking, the order is C refund, B release, A cancel. The mandatory C-failure case has no committed C, so its order is exactly B then A.

Startup lists incomplete operation IDs; an administrator chooses recovery. The application does not erase or silently finish pending work at startup. The launcher takes an operating-system file lock for the database path, preventing a second application process from using it concurrently. The lock is released even when the process is terminated. Multi-process workflow execution is outside this assignment's scope. If the storage device itself is unavailable, recovery must wait until the database can be opened again.

## Interactive demonstration

Start with a clean demo database, following the reset procedure below. Money is entered in integer cents. Example successful input: operation `ok-1`, plate `123ABC`, height `180`, space `1`, prepaid hours `2`, tender `1000`.

| Menu | Action | What to show |
|---:|---|---|
| 1 | Execute a booking with real input | A, B and C commit; workflow COMPLETED; final states HELD / RESERVED / CAPTURED |
| 2 | B-failure demonstration | Sensor blocks space 2; A commits; B rejects the obstruction; A becomes CANCELLED |
| 3 | C-failure demonstration | Reserve space 3; offer 100 cents for two hours; B becomes RELEASED before A becomes CANCELLED |
| 4 | Inspect persisted records | All three subsystem tables and workflow progress |
| 5 | Inspect history by operation ID | Ordered starts, commits, reason, compensation and final state |
| 6 | Recover incomplete operations | Resume PENDING states or retry COMPENSATING |
| 7 | Cancel by operation ID | Persist manual reversal; repeating cancellation is safe |
| 8 | Run `gate`, `spaces` or `payment` alone | Each subsystem operates through its own public API without the workflow facade |
| 9 | Stop at a durable checkpoint after A | PENDING_B with a committed HELD permit |
| 10 | Set sensor state | Mark a space `blocked` or `clear` |
| 0 | Exit | Close the store while retaining committed data |

The B demonstration changes a genuine persisted sensor reading, which the ordinary reservation rule reads. The C demonstration uses the ordinary tariff/tender rule. Neither scenario throws a special artificial demo exception. C requires space 3 to be free and unblocked. If an earlier completed booking occupies it, cancel that booking first. B leaves space 2 blocked so its cause remains visible; clear it through menu 10 when needed.

For an additional failure, enter a height of `230` cm in menu 1. A rejects the vehicle against the 220 cm clearance limit, no domain record commits, and the workflow is `REJECTED`. Non-numeric input is reported without crashing the menu.

### Reproducible command examples

After `verify`, use the jar for concise demonstrations:

```powershell
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar --db data/exam.db park ok-1 123ABC 180 1 2 1000
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar --db data/exam.db demo-b fail-b
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar --db data/exam.db demo-c fail-c
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar --db data/exam.db inspect
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar --db data/exam.db history fail-c
```

### Verify a real restart

1. Use menu 9 with a new ID, valid plate/height and an available space. It persists A and `PENDING_B` before returning to the menu.
2. Exit with menu 0, or terminate the process after the checkpoint has printed. This discards all Java objects.
3. Start a new process with the same `--db` path. It lists the incomplete ID. Use menu 4 to inspect A and `PENDING_B` before recovery.
4. Choose menu 6. B and C run from persisted input. Inspect `COMPLETED` and the preserved history.
5. To demonstrate recovery followed by compensation, block the requested space through menu 10 before recovery. B rejects it and persisted A is cancelled.

The command equivalent is:

```powershell
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar --db data/restart.db checkpoint restart-1 ABC789 180 1 2 1000
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar --db data/restart.db inspect
.\.tools\jdk-17\bin\java.exe -jar target\smart-parking.jar --db data/restart.db recover restart-1
```

Each command above is a separate process. Use a new database path if a previous run has retained a booking in that space.

### Reset the demo database

Stop every process using the demo database. Then delete only that database and its SQLite sidecar files, for example from the project root:

```powershell
Remove-Item -LiteralPath '.\data\exam.db', '.\data\exam.db-wal', '.\data\exam.db-shm' -ErrorAction SilentlyContinue
```

Replace `exam.db` with the exact demo file you intend to reset. This deliberately removes its persisted history; startup itself never resets data. A new filename, such as `--db data/exam2.db`, is an equally simple way to start fresh without deleting anything.

## Testing and measured coverage

JUnit 5 tests use clean temporary SQLite files and real persistence gateways. They assert both compensation depths, B → A event order, domain end states, restart behavior, commit/progress gaps, retry safety, input rules and legal/illegal transitions. Standalone tests instantiate one subsystem at a time. The architecture check prevents cross-subsystem dependencies.

F.I.R.S.T. is applied through local fast tests, independent temporary stores, deterministic inputs and programmatic assertions. Domain helpers make workflows read in parking terms. Genuine development commits contain tests with their functionality; Git history is not rewritten to simulate earlier work.

Verified on **16 October 2026** with `.\mvnw.cmd clean verify`: **77 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.

| Required coverage target | Covered / executable lines | Line coverage |
|---|---:|---:|
| Gate subsystem | 86 / 86 | 100.00% |
| Spaces subsystem | 110 / 110 | 100.00% |
| Payment subsystem | 96 / 96 | 100.00% |
| `ParkingFacade` | 67 / 68 | 98.53% |
| `AdministrationFacade` | 50 / 50 | 100.00% |
| Combined required scope | 409 / 410 | **99.76%** |
| Whole project, including CLI and infrastructure | 610 / 623 | **97.91%** |

These are actual JaCoCo counters, with the combined percentage weighted by executable lines. `docs/evidence/verification.json` records the eight test suites and counters. `scripts/export-verification.ps1` recreates that summary from Surefire and JaCoCo XML. The configured threshold was also tested with an intentionally impossible 101% limit: all five required targets were rejected, confirming that the checks match the intended classes/packages.

`docs/evidence/cli-verification.txt` records successful separate JVM runs for the normal path, both failure depths, oversized vehicle, independent subsystems, repeated operations and manual cancellation. It also records forcibly killing the interactive process after A committed, rejecting a second process while the lock was held, reopening the persisted `PENDING_B` state and completing recovery. Reproduce the process checks with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify-demo.ps1
```

The report is generated by `clean verify` at `target/site/jacoco/index.html`; raw counters are in `target/site/jacoco/jacoco.xml`. Surefire reports are in `target/surefire-reports/`. Maven enforces ≥80% line coverage separately for each subsystem package and each facade class. Line coverage proves which executable lines ran; it does not prove that every business case or branch is correct.

## Principle of Least Knowledge

Without a facade, a booking client needs three subsystem collaborators and must understand order, partial success, durable progress and reversal. The following fragment uses the actual public APIs and deliberately illustrates that burden; it is not the application's client implementation:

```java
void bookDirectly(GateController gate, ReservationManager spaces,
                  PaymentTerminal payments, ParkingRequest request) {
    gate.hold(request.operationId(), request.plate(), request.heightCm());
    spaces.reserve(request.operationId(), request.space());
    payments.charge(request.operationId(), request.hours(), request.tenderCents());
    // This client would also need durable progress and B -> A compensation.
}
```

The implemented client instead has one immediate workflow collaborator:

```java
Workflow book(ParkingFacade parking, ParkingRequest request) {
    return parking.park(request);
}
```

`ParkingFacade` owns sequencing and recovery; `AdministrationFacade` owns the separate operational surface. The composition root knows how objects are built, while the client does not navigate gateway or database objects. The benefit is reduced client knowledge, not simply fewer lines in the happy path.

## Facade versus Adapter

A Facade gives a client a coherent high-level entry point into an existing set of capabilities. Here, `ParkingFacade.park` coordinates gate clearance, space reservation and payment as one booking. It also decides which committed effects must be compensated after a later failure. The subsystem APIs remain independently usable.

An Adapter changes an interface so an existing component can be used where a different interface is expected. For example, a future adapter could translate a physical gate vendor's protocol into the garage's gate API. This project does not have an incompatible interface to translate. Its problem is orchestration and the amount of subsystem knowledge demanded of a client, so Facade matches the intent. An adapter at a future hardware boundary could coexist with the facades.

## Documentation and submission

- [Requirement traceability](docs/REQUIREMENTS.md) maps the original assignment to implementation and evidence.
- [UML diagram](docs/uml.svg) shows the client, facades, subsystem classes and dependency directions; its editable sources are kept beside it.
- [Formal Word report](Assignment4_Report.docx) explains the implementation and records verified results.
- [Russian defense guide](DEFENSE_GUIDE.md) covers design reasoning, a live demo, examiner questions and modification exercises.
- `docs/evidence/` contains actual verification output, not staged screenshots.

The project uses local Git commits. Inspect them with `git log --oneline` and review changes with `git status`. Generated binaries, tools, build caches, temporary rendering output and local databases are ignored. The Maven Wrapper jar is retained as bootstrap infrastructure.

No repository URL has been invented and no automatic push or Moodle submission is performed. Create or choose your own remote repository, push the reviewed local history, and submit its real URL through Moodle by your class deadline. Confirm that this domain and these subsystems were not used in your Assignments 1–3 and do not duplicate another student's work; those facts cannot be checked from this local folder. Add your name/group where required by your institution, and use the defense guide to explain and modify the implementation yourself.


