# Data dictionary

All data is synthetic. Dates use ISO-8601 (`YYYY-MM-DD`), timestamps use ISO local date-time text without a timezone offset, percentages are stored as decimal ratios from 0 to 1, and grade scores use a 0–100 scale. Empty CSV values represent unknown or inapplicable values, not zero. Every entity has a required immutable primary key. Foreign keys must resolve to the referenced table. IDs are unique within their entity.

## Core tables

| Entity | Fields (type; required/optional; allowed values; validation) |
|---|---|
| Student | `student_id` (string, required, PK, `STU-2026-######`); `cohort_year` (integer, required, 2026); `created_at` (date, required, ISO date). Synthetic IDs only; no personal attributes. |
| Course | `course_id` (string, required, PK, `CRS-######`); `course_name` (string, required, unique); `department` (enum: `SCIENCE`, `ARTS`, `BUSINESS`, `TECHNOLOGY`, `GENERAL`); `credits` (integer, required, 1–6). |
| Enrollment | `enrollment_id` (string, required, PK, `ENR-<student-id>-<course-id>`); `student_id` and `course_id` (string, required, FKs); `start_date` (date, required); `end_date` (date, optional, >= start); `status` (enum: `ACTIVE`, `WITHDRAWN`, `TRANSFERRED`, `COMPLETED`); `issue_type` (optional; reserved for explicit data-quality labels). Unique student/course pair; dates bound all associated events. |
| AttendanceEvent | `attendance_id` (string, required, PK, `ATT-<enrollment-id>-<date>`); `enrollment_id` (string, required, FK); `event_date` (date, required, within enrollment dates); `status` (enum: `PRESENT`, `ABSENT`, `APPROVED_ABSENCE`, `UNKNOWN`); `source` (enum: `SYNTHETIC_LMS`, `MANUAL`). One event per enrollment and scheduled date. |
| Assessment | `assessment_id` (string, required, PK, `ASM-<course-id>-<sequence>`); `course_id` (string, required, FK); `title` (string, required); `due_date` (date, required); `max_score` (decimal, required, 100); `sequence_no` (integer, required, 1–4). |
| AssessmentResult | `result_id` (string, required, PK); `enrollment_id` and `assessment_id` (string, required, FKs); `score` (decimal, optional, 0–100); `status` (enum: `GRADED`, `MISSING`, `INCOMPLETE`); `recorded_at` (timestamp, required, >= due date). A result is unique for each enrollment/assessment pair and is emitted only if the assessment falls within the enrollment's effective dates; missing values are not scored as failures. |
| LMSActivity | `activity_id` (string, required, PK, `LMS-<enrollment-id>-<YYYYMMDD>-##`); `enrollment_id` (string, required, FK); `activity_at` (timestamp, required, within enrollment dates); `activity_type` (enum: `LOGIN`, `RESOURCE_VIEW`, `PRACTICE`, `DISCUSSION`); `duration_minutes` (integer, required, 1–120). Multiple activities per enrollment per week are allowed. |
| SupportCase | `case_id` (string, required, PK, `CASE-2026-######`); `enrollment_id` (string, required, FK); `opened_at` (timestamp, required, on/after risk trigger); `status` (enum: `OPEN`, `IN_PROGRESS`, `CLOSED`); `case_type` (enum: `ACADEMIC_OUTREACH`, `DATA_QUALITY_REVIEW`); `trigger_risk_id` (string, required, FK to risk assessment). One optional case per enrollment in generated baseline. |
| Intervention | `intervention_id` (string, required, PK, `INT-2026-######`); `case_id` (string, required, FK); `intervention_type` (enum: `TUTORING_REFERRAL`, `ADVISOR_OUTREACH`, `DATA_REVIEW`); `assigned_role` (enum: `INSTRUCTOR`, `SUPPORT_STAFF`, `ACADEMIC_ADMIN`); `scheduled_at` (timestamp, required, on/after case trigger); `status` (enum: `SCHEDULED`, `COMPLETED`, `DECLINED`, `NO_RESPONSE`); `outcome` (optional, same enum as status except `SCHEDULED`). |
| Outcome | `outcome_id` (string, required, PK); `enrollment_id` (string, required, FK); `observed_from` and `observed_to` (dates, required, ordered, after risk cutoff and no later than enrollment end); `future_attendance_rate` (decimal, optional, 0–1); `future_average_grade` (decimal, optional, 0–100); `label` (enum: `ON_TRACK`, `AT_RISK`, `INCOMPLETE`); `label_rule` (string, required, documents deterministic rule). Evaluation-only; never a model input. |

## Decision, lifecycle, and evaluation tables

| Entity | Fields and validation |
|---|---|
| RiskAssessment | `risk_id` (PK); `enrollment_id` (FK); `as_of_date`; `attendance_rate` (optional, 0–1); `average_grade` (optional, 0–100); `lms_events_per_week` (optional, nonnegative); `attendance_points`, `grade_points`, `engagement_points` (integers); `score` (optional, 0–100); `decision` (`LOW`, `MEDIUM`, `HIGH`, `INSUFFICIENT_DATA`, `EXCLUDED`); `explanation` (required). Features are restricted to dates <= `as_of_date`; no outcome fields. `EXCLUDED` is reserved by the schema and is not emitted by this generator. |
| CaseEvent | `case_event_id` (PK); `case_id` (FK); `event_at`; `from_status` (optional); `to_status`; `event_type`. Append-only status history; timestamps ordered. |
| InterventionEvent | `intervention_event_id` (PK); `intervention_id` (FK); `event_at`; `from_status` (optional); `to_status`; `event_type`. Append-only status history; timestamps ordered. |
| GroundTruth | Evaluation-only CSV/JSON/SQL with `record_id`, `enrollment_id`, `outcome_label`, `expected_risk_band` (coarse evaluation band derived from future label), and a documented label rule. Never join into feature generation. |
| EdgeCase | `issue_id`, `issue_type`, `entity_key`, `expected_behavior`, `faulty_example`, `clean_value`, `severity`. Explicitly isolated negative-scenario fixtures; not included in baseline facts. |

## Relationships and lifecycle

- Student 1:N Enrollment and Course 1:N Enrollment; Student and Course are N:M through Enrollment.
- Enrollment 1:N AttendanceEvent, AssessmentResult, and LMSActivity.
- Course 1:N Assessment; Assessment 1:N AssessmentResult. Each Enrollment/Assessment pair has at most one result.
- Enrollment 1:0..1 RiskAssessment, SupportCase, and Outcome in this generated evaluation.
- RiskAssessment 1:0..1 SupportCase; SupportCase 1:N Intervention; SupportCase and Intervention each 1:N lifecycle events.
- Enrollment lifecycle states are `ACTIVE`, `WITHDRAWN`, `TRANSFERRED`, and `COMPLETED`; end date is required for non-active records and absent for active records. Generated withdrawals/transfers occur after the risk cutoff.
- SupportCase transitions `OPEN` → `IN_PROGRESS` → `CLOSED` (or remains `OPEN`). Intervention transitions `SCHEDULED` → one terminal status. Event tables preserve each transition.
- Tutoring referrals are assigned to instructors, advisor outreach to support staff, and data-quality reviews to academic administrators. Learners are the recipients; the platform administrator maintains the generator and rules.

## Validation and calculation rules

Attendance denominator includes only `PRESENT` and unapproved `ABSENT`. Approved absences and `UNKNOWN` are excluded; fewer than four eligible observations is insufficient data. A risk assessment uses weeks 1–12 only. Future outcomes use weeks 13–36, truncated at the enrollment end date, and are kept in distinct tables. Percentages and scores are derived from their source events/results. Missing data is never imputed as failure.
