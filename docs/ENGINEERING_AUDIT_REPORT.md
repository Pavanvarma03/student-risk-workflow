# Engineering Audit Report

## 1. Project overview

The project is a Java 17/Maven command-line generator for deterministic synthetic student-risk data. It writes CSV tables, a SQLite-compatible schema seed, support-case and intervention histories, separately labeled future outcomes, anomaly fixtures, provenance, checksums, and evaluation metrics. It does not contain a trained predictive model or a live student-support service.

Audit scope included the CLI, generator, tests, Maven configuration, schema, data dictionary, README, starter output, and full output. No Git commands that change repository state or history were run; this workspace is not a Git repository.

## 2. Findings and fixes

| ID | Severity | Finding and evidence | Status |
|---|---|---|---|
| F-01 | Medium | Invalid CLI values previously surfaced as JVM exception traces instead of concise user errors. | Fixed: argument errors return exit code 2 with a message; I/O failures return exit code 1. CLI subprocess tests cover unknown, missing, nonnumeric, out-of-range, and invalid-path inputs. |
| F-02 | Medium | Positive but extreme scale values could reach large list allocations and exhaust memory. | Fixed: CLI scales are bounded at 100,000 students, 10,000 courses, and 50,000 enrollments; products are checked using `long`. The limits reduce accidental high-resource requests but do not guarantee the largest accepted scale fits every machine. The largest accepted scale was not benchmarked. |
| F-03 | Medium | A failed rerun could leave an older validation summary next to partially overwritten data. | Fixed: the prior completion summary is removed before output files are opened. A failure-path test verifies it is not left behind. Partial CSV/SQL files can still remain; output is not published atomically. |
| F-04 | Medium | The generated summary claimed broad foreign-key, enum, date, and metric validation that its internal checks did not perform. | Fixed: summary fields now name only checks performed by the generator. Independent SQLite validation was run separately for this audit. |
| F-05 | Low | Documentation had inaccurate timestamp and LMS identifier formats and did not explain CLI precedence or scale limits. | Fixed in the data dictionary and README. |
| F-06 | Low | Unused imports and an unused CSV-table path field remained in the generator. | Removed without changing generated values. |
| F-07 | Informational | The generator remains a single class with generation, reporting, and file-writing responsibilities. | Not refactored: preserving the working CLI architecture was lower risk for this audit. A future focused decomposition is recommended. |

No risk-score or outcome-calculation defect was reproduced. Independent recalculation from the generated SQLite data agreed with the stored prior-period risk features, scores, future metrics, labels, and evaluation counts.

## 3. Bugs fixed

- Made `--full` a baseline scale selector whose explicit custom dimension values take precedence regardless of argument order.
- Added readable numeric parsing errors and nonzero process exit codes for invalid arguments and I/O failures.
- Added explicit scale bounds to prevent unbounded initial allocations.
- Invalidated a stale validation-summary marker before starting a generation attempt.
- Restricted validation-summary claims to checks the generator actually runs.
- Removed unused code elements and corrected documentation to match emitted identifiers and timestamps.

The output directory is still written in place. If a run fails, partially written files may remain, but the completion summary is absent. Atomic staging and directory replacement were not introduced.

## 4. Files modified

- `README.md`
- `docs/data-dictionary.md`
- `src/main/java/org/example/studentrisk/RiskDataGenerator.java`
- `src/test/java/org/example/studentrisk/RiskDataGeneratorTest.java`
- `docs/ENGINEERING_AUDIT_REPORT.md` (this report)
- `sample-output/` (regenerated starter CSV, JSON, and SQL files)
- `full-output/` (regenerated full-scale CSV, JSON, and SQL files; ignored and excluded from the ZIP)

The Maven configuration and SQL schema were inspected and did not require changes. The ZIP is generated separately from the source, docs, and starter output; it excludes `target/` and `full-output/`.

## 5. Tests added

The JUnit suite now has eight tests. In addition to scoring-boundary, invalid-scale, joins, and feature-reconciliation cases, it covers:

- Default CLI execution and generated starter counts.
- Default/full/custom scale parsing and custom overrides of `--full`.
- Unknown options, missing values, invalid numbers, oversized values, zero/negative scales, and invalid output paths.
- Repeat execution into an existing output directory and same-seed reproducibility.
- Different-seed output differences.
- Failure to create output files without leaving an old completion summary.
- SQL seed transaction/header output and evaluation-metric reconciliation.

## 6. Tests executed and results

| Check | Result |
|---|---|
| `mvn clean test` | **Passed** — 8 tests, 0 failures, 0 errors, 0 skipped. |
| `mvn package` | **Passed** — executable JAR built; 8 tests, 0 failures, 0 errors, 0 skipped. |
| Starter generation | **Passed** — 100 students, 20 courses, 400 enrollments. |
| Full generation | **Passed** — 5,000 students, 200 courses, 20,000 enrollments. |
| Custom CLI execution | **Passed** — explicit seed, dimensions, output directory, and `--full` overrides reflected in the manifest. |
| SQLite seed import | **Passed** for starter and full output using Node.js `node:sqlite` 3.53.1; `PRAGMA foreign_key_check` returned no violations. |
| PostgreSQL import | **Not run** — the project schema and seed use SQLite syntax. A local PostgreSQL readiness probe reported no server listening on port 5432. |

The unit test suite reports no skipped tests. Locked-file behavior, permissions failures, process interruption, and disk-full behavior were not induced; safely reproducing those conditions was outside this local test run.

## 7. Data-integrity results

Independent checks loaded each generated `seed.sql` into an in-memory SQLite database created from `docs/schema.sql`. A streaming CSV scan checked field counts across every row, compared each relational CSV row count with its imported SQL table, and ran referential, chronology, and derived-value queries.

- Starter: **34,279 data rows** across relational CSV files; CSV and SQL counts reconciled.
- Full: **1,712,547 data rows** across relational CSV files; CSV and SQL counts reconciled.
- Both datasets: primary/foreign-key constraints passed; event dates fell within enrollment effective dates; assessment results matched course and effective-date relationships; support cases followed their risk triggers; lifecycle event timestamps were ordered.
- Risk features and component scores independently recalculated from week-12-or-earlier attendance, assessments, and LMS activity matched the stored decisions. Future outcomes independently recalculated from later events matched stored metrics and labels.
- Evaluation confusion-matrix counts matched the risk and outcome rows. Ground-truth outcome labels matched the separate outcome table.
- The checks confirm generated output for these seeds and scales; they do not prove all possible input configurations valid.

## 8. Reproducibility

- The JUnit suite generated identical outputs twice for a small fixed-seed configuration and confirmed a different seed produced different checksums.
- The starter dataset was regenerated with the same seed and scale; SHA-256 hashes matched for all 20 output files.
- The full 20,000-enrollment dataset was generated twice with seed `20261022`. SHA-256 hashes matched for all 20 output files.
- Generated manifests contain seed, scale, generator version, and table counts. Validation-summary checksums cover output files other than the summary itself.
- No runtime timestamps or other intentionally nondeterministic metadata are emitted.

## 9. Performance measurements

Measured on Windows 11 with OpenJDK 17.0.19, using `-Xmx2g`, seed `20261022`, separate output directories, and a 50 ms working-set sampling interval. Sizes are the total generated output directory size. These are single-run, environment-specific measurements, not performance guarantees.

| Students | Courses | Enrollments | Wall time | Process CPU time | Sampled peak working set | Output size |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 20 | 400 | 2.19 s | 6.16 s | 226.5 MB | 11.9 MB |
| 1,000 | 50 | 4,000 | 8.30 s | 18.30 s | 348.0 MB | 118.8 MB |
| 5,000 | 200 | 20,000 | 18.88 s | 39.45 s | 390.5 MB | 594.2 MB |
| 10,000 | 200 | 40,000 | 34.04 s | 59.61 s | 394.5 MB | 1,190.0 MB |

Process CPU time is cumulative across process threads and can exceed wall time. Working set was sampled and may miss short peaks. No profiler-based CPU breakdown was run. The maximum accepted configuration (50,000 enrollments) was not run.

## 10. Security and privacy

- The generator uses synthetic identifiers and attributes only; it has no real learner data, personal contact fields, credentials, or external data source.
- A targeted credential-pattern scan of source, documentation, and project metadata found no matches. Text scans for promotional tool attributions, copied prompts, and unfinished work markers across source, documentation, and starter/full generated files also found no matches.
- SQL table and column names are fixed in the generator; SQL values are quoted/escaped or emitted as numeric literals. No external SQL input is accepted.
- The output path is user-selected and generated filenames are overwritten within that directory. Users should select an output directory they control.
- No authentication, authorization, encryption at rest, access logging, retention controls, or consent workflow exists. These safeguards and institutional/privacy review are prerequisites before handling real student information.
- No dependency vulnerability scanner was available/run. The application has no runtime third-party dependency; JUnit 5 is test-scoped, with Maven build plugins as configured in `pom.xml`.

## 11. AI/ML readiness

There is **no trained model**. The project implements a deterministic rules-based score using attendance, early grades, and engagement. Independent checks found no future-period features in the risk assessment calculation; future outcome values and ground truth are kept separate.

The full synthetic set reports 19,608 evaluated enrollments and 392 excluded records. Under the documented positive rule (MEDIUM/HIGH risk versus future AT_RISK), it reports TP=5,547, FP=5,883, FN=724, TN=7,454; accuracy=0.6630, precision=0.4853, recall=0.8845, and F1=0.6267. These measure agreement with this generator's synthetic label rule only. They are not real-world model performance or causal evidence of intervention effectiveness.

No demographic attributes are generated, so subgroup fairness cannot be assessed. Any future model or real-data deployment needs domain review, lawful data governance, subgroup analysis, calibrated thresholds, and human review of consequential decisions.

## 12. Remaining limitations

- A failed generation can leave partial files. The summary marker is invalidated first, but the output directory is not staged and atomically published.
- Internal generation validation is intentionally narrower than the independent checks performed for this audit. SQL constraints are selected rather than exhaustive; additional event chronology and lifecycle constraints could be enforced in a reusable validator.
- Scale limits are documented; the upper bounds have not been tested.
- Output writing includes both CSV and SQL and retains enrollment and ground-truth records in memory. Larger workloads need profiling before raising the bounds.
- Synthetic data, shared latent generation factors, and rule-derived labels limit conclusions about actual learners.
- The class still combines CLI parsing, generation, reporting, and CSV/SQL writing.
- No live database service, web UI, operational support workflow, or ML model is included.

## 13. Recommended next improvements

1. Write to a staging directory and publish only after all outputs and checks complete; define safe replacement behavior for existing output.
2. Move the independent relational and derived-metric checks into a repeatable validator and CI test.
3. Split CLI/configuration, domain calculations, and output writers into focused classes while preserving the command-line interface.
4. Profile larger workloads before changing scale limits; consider a configurable output format if SQL seed size becomes a bottleneck.
5. If real student data is ever in scope, complete privacy, legal, security, accessibility, and fairness reviews before implementation.

## 14. Reproduction commands

From the project root:

```shell
mvn clean test
mvn package
java -jar target/student-risk-workflow-1.0.0.jar --output sample-output
java -Xmx2g -jar target/student-risk-workflow-1.0.0.jar --full --output full-output
```

Independent SQLite validation in this audit used Node.js 24.18.0 with its built-in `node:sqlite` module; it did not require adding a project dependency.
