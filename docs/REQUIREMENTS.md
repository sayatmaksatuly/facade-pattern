# Assignment 4 requirement traceability

Authoritative source: `Assignment_4_Facade patterm (2).docx`, read in full before implementation. The source repeats section number 6 for the domain note and individual variants; those headings are preserved below. Assigned domain: **Smart Parking Garage, variant 20**.

Final local audit: **16 October 2026**. This checklist records inspected implementation and executed evidence. The verified Maven build passed 77 tests with no failures, errors or skipped tests. Actual results are preserved in `docs/evidence/verification.json` and `docs/evidence/cli-verification.txt`. Student-only submission and originality checks are explicitly separated below.

## Official mandatory requirements

| Source | Requirement | Implementation and evidence |
|---|---|---|
| 1, 2 | One client operation coordinates independent A → B → C; durable progress; live demonstration | `ParkingFacade`, `ParkingApplication`, persisted workflow journal; integration tests and CLI evidence |
| 3.1 | 3–5 meaningful classes in each subsystem, 9–15 total | A: `GateAdmission`, `LicensePlateReader`, `GateGateway`, `GateController`; B: `ParkingReservation`, `SpaceSensorGrid`, `ReservationGateway`, `ReservationManager`; C: `ParkingPayment`, `OverstayAlert`, `PaymentGateway`, `PaymentTerminal` |
| 3.1 | Pure business logic separate from entities and storage | `LicensePlateReader`, `SpaceSensorGrid`, `OverstayAlert`; direct unit tests |
| 3.1 | Each subsystem has its own direct persistence gateway | `GateGateway`, `ReservationGateway`, `PaymentGateway`; SQLite-backed tests |
| 3.1 | Each entity has at least three meaningful statuses and independently enforces legal transitions | Gate: HELD, ADMITTED, CANCELLED; reservation: RESERVED, OCCUPIED, RELEASED; payment: AUTHORIZED, CAPTURED, REFUNDED; transition tests |
| 3.1 | No cross-subsystem imports, calls, or knowledge | Separate `gate`, `spaces`, `payment` packages; architecture checks |
| 3.1 | Independently usable outside facade | Standalone subsystem demonstration and isolated subsystem tests |
| 3.2 | At least two real facades, including administrative inspection/manual compensation | `ParkingFacade` and `AdministrationFacade`; constructor injection; actual orchestration and recovery decisions |
| 3.3 | Durable entities and workflow progress survive restart | SQLite file, independent committed stages, persisted journal, close/reopen tests and process restart evidence |
| 3.4 | B fails after durable A; compensate A alone | Unavailable parking space; inspect persisted states and history; dedicated test and CLI scenario |
| 3.4 | C fails after durable A and B; compensate B then A | Insufficient payment tender; assert durable compensation order and end states; dedicated test and CLI scenario |
| 3.5 | Concrete Least Knowledge before/after using actual classes in README | Direct `GateController` / `ReservationManager` / `PaymentTerminal` calls compared with one `ParkingFacade` operation |
| 3.6 | At least one additional realistic failure and appropriate custom exceptions | Vehicle height / license plate / input validation; domain exceptions; tests and CLI handling |
| 3.7 | Fast, independent, repeatable tests with clean known persisted state | JUnit 5 temporary SQLite files, no order dependence, assertions, no network dependency |
| 3.7 | Tests committed alongside implementation | Genuine incremental Git commits containing production code and associated tests; no fabricated history |
| 3.7 | Domain-specific testing helper or DSL | Named test helper methods expressing parking behavior |
| 3.7 | First-class B and C compensation tests and persisted end states | Workflow integration tests with actual SQLite gateways |
| 3.7 | Store closed/reopened mid-chain; facade uses persisted status | Restart/recovery integration tests |
| 3.7 | ≥80% line coverage across all three subsystems and both facades | Actual JaCoCo report and Maven verification threshold; measured values in README/report |
| 3.8 | Runnable user-facing application with real input, inspection and both failures on demand | Interactive console menu; Windows launcher; verified CLI transcript |
| 3.9 | UML source plus rendered diagram matches finished code | `docs/uml.puml`, `docs/uml.svg`; source-to-diagram audit |
| 3.10 | Facade vs Adapter reflection, half a page or less, grounded in this domain | README and report reflection |

## Constraints and deliverables

| Source | Constraint or deliverable | Evidence or responsibility |
|---|---|---|
| 4 | Do not use lecture examples | Variant 20 is the assigned Smart Parking Garage domain |
| 4 | Do not reuse Assignment 1–3 domains/subsystems | Student must confirm against their previous submissions |
| 4 | Do not use fake in-memory persistence | Real SQLite file plus restart evidence |
| 4 | No hidden subsystem dependencies | Package/import inspection and architecture tests |
| 4 | Individual work; no identical or near-identical peer domain | Student/instructor confirmation cannot be established from local files |
| 5 | Buildable repository with a single run command | Maven Wrapper launcher, project structure and README |
| 5 | Repository link and Moodle submission before announced deadline | User must publish and submit; no URL or submission claim invented |
| 5 | Working demo and reset instructions | README demonstration and explicit database reset procedure |
| 5 | UML, README, reflection, Least Knowledge example | Documentation deliverables |
| 5 | Tests and coverage report from standard command | `mvnw.cmd clean verify` and JaCoCo report |
| Domain note | A holds, B executes, C finalizes; realistic failure depths | Gate admission hold → space reservation → payment confirmation |
| Individual variants | Variant 20 class starting points | Gate Controller / License Plate Reader; Space Sensor Grid / Reservation Manager; Payment Terminal / Overstay Alert |

## User-requested additions

| Requirement | Verified evidence |
|---|---|
| Java 17, Maven, JUnit 5, JaCoCo, SQLite, local CLI, Git | `pom.xml`, wrapper, source, tests, repository |
| Retry-safe recovery and compensation; explicit transaction boundaries | Persisted workflow, idempotent gateways, repeated operation tests |
| Invalid input, unexpected exceptions, partial recovery | CLI validation and recovery tests |
| Report as real Word document | `Assignment4_Report.docx`, valid Word OOXML, nine pages; every page rendered through Microsoft Word PDF export and inspected as PNG |
| README with measured results, Windows usage and full demonstration | `README.md` |
| Russian defense guide with ≥15 questions and 3–5 live coding exercises | `DEFENSE_GUIDE.md` |
| UML source and rendered image/vector | `docs/uml.puml`, `docs/uml.svg` |
| Clean project; exclude binaries and local data; do not push | `.gitignore`, meaningful local commits only |
| Actual process termination/restart | Saved CLI verification transcripts and persisted state inspection |
| Final audit against original source | This checklist reviewed against final source code, all original mandatory sections and recorded evidence |

## Official grading emphasis

| Component | Weight | Preparation |
|---|---:|---|
| Defense and live coding | 50% | Clear classes, Russian guide, failure/recovery explanations, live modification exercises |
| Unit tests | 25% | F.I.R.S.T., both failure depths, persisted assertions, restart tests and measured coverage |
| Working demo | 10% | Real interactive input, failure output, inspection, manual recovery |
| Documentation | 10% | README, UML, Facade vs Adapter reflection, formal report |
| Subsystems, facades and persistence | 5% | Structural independence, explicit dependencies, statuses and SQLite |

No grade is predicted. Historical authorship, uniqueness among students, instructor approval and Moodle submission remain student responsibilities.

## Final audit results

| Check | Result | Evidence |
|---|---|---|
| Domain and class boundaries | PASS | Smart Parking Garage variant 20; four classes per subsystem; `ArchitectureTest` checks separation |
| Main and administrative facades | PASS | `ParkingFacade` orchestration and `AdministrationFacade` inspection/recovery/manual cancellation |
| Normal workflow and both compensation depths | PASS | Real CLI process transcript plus persisted-state assertions in `ParkingFacadeTest` |
| Additional failure and independent use | PASS | Oversized vehicle REJECTED; all three standalone commands and subsystem tests |
| Restart and incomplete-operation recovery | PASS | JVM forcibly killed after A committed; another JVM reads HELD/PENDING_B and completes recovery |
| Idempotency and interrupted compensation | PASS | Repeated operations, commit/journal gaps, interrupted compensation and event order covered by tests |
| Build and tests | PASS | `.\mvnw.cmd clean verify`: 77 tests, 0 failures, 0 errors, 0 skipped |
| Required line coverage | PASS | Gate 86/86, spaces 110/110, payment 96/96, ParkingFacade 67/68, AdministrationFacade 50/50; combined 409/410 = 99.76% |
| Whole-project line coverage | MEASURED | 610/623 = 97.91%; distinct from the required subsystem/facade scope |
| Coverage threshold selection | PASS | Deliberate 101% check rejected all five configured required targets |
| Windows launcher | PASS | Maven Wrapper launch enters interactive CLI and exits cleanly; packaged jar also verified |
| UML | PASS | `uml.puml` and `uml.svg` generated from `generate_uml.py`; client and associations inspected against final source |
| README and defense guide | PASS | Actual APIs, Windows commands, reset, both failures, Least Knowledge, short reflection; Russian guide with 20 questions and 5 exercises |
| Formal report | PASS | `Assignment4_Report.docx`, nine readable pages, Times New Roman body 14 pt, real evidence and measured results |
| Local Git history | PASS | Genuine implementation and associated tests committed together; no rewritten history or automatic push |
| Prior-assignment and peer originality | STUDENT CHECK | Requires the student's earlier submissions and instructor context |
| Remote repository URL and Moodle submission | STUDENT ACTION | No URL invented and no submission claimed |

The packaged `render_docx.py` renderer was attempted but the Windows bundle has no LibreOffice executable. The report was instead exported read-only through installed Microsoft Word 2016, rasterized with bundled Poppler and inspected page by page. QA intermediates remain in ignored `docs/.qa/`; the submission deliverable is the DOCX.
