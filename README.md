# Student Risk Workflow

## Overview

A Java command-line data generator for an explainable student-risk and intervention workflow. It produces deterministic synthetic academic records, scores risk from prior-period signals, creates support cases, and labels later outcomes separately for evaluation.

## Problem statement

Academic teams need a traceable way to identify learners who may need support using attendance, assessment performance, and learning-platform engagement. This project demonstrates that workflow with synthetic data, explicit decision factors, documented exceptions, and evaluation metrics. It is a data-generation and evaluation example, not a production student-monitoring service.

## Architecture

`RiskDataGenerator` parses command-line options and generates parent records before dependent events. It writes CSV tables, a SQLite-compatible insert script, ground-truth labels, anomaly fixtures, provenance, checksums, and evaluation metrics. Risk features are limited to the first 12 academic weeks; outcomes use later records. `docs/schema.sql` defines relational constraints, and the JUnit suite checks reproducibility, joins, risk cutoffs, metric reconciliation, scoring boundaries, and invalid scales.

The generator is deterministic for a given seed and scale. Its runtime and output size grow approximately linearly with the number of enrollments and generated weekly events.

## Technology

- Java 17
- Maven
- JUnit 5
- CSV and JSON outputs
- SQLite-compatible SQL schema and seed script

## Features

- Stable student, course, enrollment, event, case, and intervention identifiers.
- Synthetic attendance, graded/missing/incomplete assessment results, and timestamped LMS activity.
- Explainable attendance, early-grade, and engagement score components.
- A prior-period cutoff that keeps future outcome records out of risk inputs.
- Support cases, role-assigned interventions, lifecycle event history, and varied intervention outcomes.
- Separate future-outcome labels, anomaly fixtures, and binary evaluation metrics.
- Fixed-seed generation, count summaries, and SHA-256 checksums.

## Project structure

```text
student-risk-workflow/
├── docs/
│   ├── ENGINEERING_AUDIT_REPORT.md
│   ├── data-dictionary.md
│   └── schema.sql
├── sample-output/                 # Small reproducible example dataset
├── src/
│   ├── main/java/org/example/studentrisk/
│   │   └── RiskDataGenerator.java
│   └── test/java/org/example/studentrisk/
│       └── RiskDataGeneratorTest.java
├── .gitignore
├── pom.xml
└── README.md
```

`full-output/` is generated on demand and is not required in the source archive.

## Installation

Install a Java 17 (or newer) JDK and Maven 3.8 (or newer). From the project root, confirm they are available:

```shell
java -version
mvn -version
```

Maven resolves the build and test dependencies on the first build.

## Build and tests

```shell
mvn clean test
mvn package
```

The tests cover repeatable outputs for a fixed seed, parent and child ID joins, risk input date boundaries, attendance/grade/engagement calculation reconciliation, evaluation-metric counts, scoring thresholds, and invalid scale rejection. `mvn package` also runs the test phase.

## Generate data

Run commands from the project root. The default starter scale is 100 students, 20 courses, and 400 enrollments:

```shell
java -jar target/student-risk-workflow-1.0.0.jar --output sample-output
```

Generate the full baseline of 5,000 students, 200 courses, and 20,000 enrollments:

```shell
java -Xmx2g -jar target/student-risk-workflow-1.0.0.jar --full --output full-output
```

Both use seed `20261022`. A custom seed and scale can be selected explicitly:

```shell
java -jar target/student-risk-workflow-1.0.0.jar --students 200 --courses 40 --enrollments 1200 --seed 12345 --output custom-output
```

| Option | Description | Default |
|---|---|---|
| `--full` | Select the 5,000/200/20,000 starter full-scale dimensions. | Off |
| `--students N` | Set the student count. | 100, or 5,000 with `--full` |
| `--courses N` | Set the course count. | 20, or 200 with `--full` |
| `--enrollments N` | Set the enrollment count. | 400, or 20,000 with `--full` |
| `--seed N` | Set the deterministic random seed (signed 64-bit integer). | `20261022` |
| `--output DIR` | Set the output directory. | `sample-output` |

All scale values must be positive. The configured limits are 100,000 students, 10,000 courses, and 50,000 enrollments; enrollments cannot exceed students multiplied by courses. These bounds limit accidental high-resource requests, but the largest accepted scale has not been benchmarked. The full run produces up to 36 attendance events and 72 LMS activity events per enrollment, plus up to four assessment results, risk assessments, support cases, interventions, and outcomes. Explicit custom scale values override `--full`, regardless of their argument order.

Unknown options, missing values, invalid numbers, and unsupported scales return a nonzero exit code and a concise error message. I/O failures also return a nonzero exit code. If generation fails after it starts, no `validation_summary.json` is left to indicate a successful run; other partially written output files may remain.

## Dataset files

Each generated directory contains:

- Core CSVs: `students.csv`, `courses.csv`, `enrollments.csv`, `attendance_events.csv`, `assessments.csv`, `assessment_results.csv`, and `lms_activities.csv`.
- Workflow CSVs: `risk_assessments.csv`, `support_cases.csv`, `interventions.csv`, `case_events.csv`, and `intervention_events.csv`.
- Evaluation CSV/JSON: `outcomes.csv`, `ground_truth.csv`, `ground_truth.json`, and `evaluation_metrics.json`.
- Quality and provenance files: `edge_cases.csv`, `source_manifest.json`, and `validation_summary.json`.
- `seed.sql`, containing table inserts in parent-before-child order.

Data is entirely synthetic. Approved absences and unknown attendance are retained as distinct statuses; assessment results can be graded, missing, or incomplete. Enrollments include active, completed, withdrawn, and transferred states. Withdrawals/transfers have effective end dates, and later events are not generated for those enrollments. Ground-truth labels and edge-case fixtures are evaluation material, not risk features.

## Risk and outcome rules

The risk assessment is dated at the end of week 12 and uses only information available through that cutoff. Attendance is `PRESENT / (PRESENT + ABSENT)`; approved absences and unknown events do not enter the denominator. Fewer than four eligible attendance events or no graded early assessment results produces `INSUFFICIENT_DATA`.

The score adds up to 100 points: attendance below 70%, 80%, or 90% adds 45, 30, or 15; an early average grade below 55, 65, or 75 adds 35, 25, or 10; and LMS activity below one or two events per week adds 20 or 10. Scores of 50 or more are `HIGH`, 25–49 are `MEDIUM`, and lower scores are `LOW`. A case is created for high risk or insufficient data.

Future outcomes are calculated separately from week 13 through the enrollment end date (up to week 36). `ON_TRACK` requires future attendance of at least 75% and a future average assessment score of at least 65; missing future metrics yield `INCOMPLETE`. Evaluation metrics treat `MEDIUM`/`HIGH` as positive risk and `AT_RISK` as a positive outcome, excluding insufficient or incomplete records. These metrics describe this synthetic dataset only; they are not evidence of real-world predictive performance or intervention impact.

## Validation and database

Generation checks student/course/enrollment key uniqueness, enrollment parent references and lifecycle dates, ground-truth references, and reconciled one-to-one counts for enrollment, risk, outcome, and ground-truth records. The test suite additionally checks generated data joins, derived risk features, scoring boundaries, and deterministic output. `validation_summary.json` reports the generation checks and file checksums; it does not claim independent validation of every event-table constraint.

`docs/schema.sql` defines SQLite tables, primary/foreign keys, unique constraints, and selected enum and value checks. Create the schema before loading a seed:

```shell
sqlite3 risk.db < docs/schema.sql
sqlite3 risk.db < sample-output/seed.sql
```

Use `full-output/seed.sql` instead for the full dataset. The SQL seed contains relational table data; JSON evaluation metrics and edge-case fixtures remain in their corresponding files. See [docs/data-dictionary.md](docs/data-dictionary.md) for field definitions, relationships, and lifecycle rules.

## Limitations and future improvements

- The generator is a command-line batch example; it has no user interface, authentication, authorization, live integrations, or operational case-management service.
- Risk thresholds, synthetic signals, and outcome rules are illustrative and require domain review before any real-world use.
- Generated evaluation metrics measure agreement with the project's deterministic synthetic label rule, not causal intervention effectiveness or generalizable model performance.
- The SQL schema enforces selected constraints; additional event chronology, cross-table date, and lifecycle-transition checks could be implemented in database triggers or a dedicated validator.
- Future work could add richer temporal evaluation, configuration files, a database-backed workflow, and documented reviews with academic and support stakeholders.

## Data provenance

No external datasets or real learner information are used. The source manifest records the seed, scale, generator version, and synthetic-only provenance.
