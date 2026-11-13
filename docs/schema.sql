PRAGMA foreign_keys = ON;

CREATE TABLE Student (
  student_id TEXT PRIMARY KEY,
  cohort_year INTEGER NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE Course (
  course_id TEXT PRIMARY KEY,
  course_name TEXT NOT NULL UNIQUE,
  department TEXT NOT NULL CHECK (department IN ('SCIENCE','ARTS','BUSINESS','TECHNOLOGY','GENERAL')),
  credits INTEGER NOT NULL CHECK (credits BETWEEN 1 AND 6)
);
CREATE TABLE Enrollment (
  enrollment_id TEXT PRIMARY KEY,
  student_id TEXT NOT NULL REFERENCES Student(student_id),
  course_id TEXT NOT NULL REFERENCES Course(course_id),
  start_date TEXT NOT NULL,
  end_date TEXT,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','WITHDRAWN','TRANSFERRED','COMPLETED')),
  issue_type TEXT,
  UNIQUE(student_id, course_id),
  CHECK (end_date IS NULL OR end_date >= start_date),
  CHECK ((status = 'ACTIVE' AND end_date IS NULL) OR (status <> 'ACTIVE' AND end_date IS NOT NULL))
);
CREATE TABLE Assessment (
  assessment_id TEXT PRIMARY KEY,
  course_id TEXT NOT NULL REFERENCES Course(course_id),
  title TEXT NOT NULL,
  due_date TEXT NOT NULL,
  max_score REAL NOT NULL CHECK (max_score = 100),
  sequence_no INTEGER NOT NULL CHECK (sequence_no BETWEEN 1 AND 4)
);
CREATE TABLE AttendanceEvent (
  attendance_id TEXT PRIMARY KEY,
  enrollment_id TEXT NOT NULL REFERENCES Enrollment(enrollment_id),
  event_date TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('PRESENT','ABSENT','APPROVED_ABSENCE','UNKNOWN')),
  source TEXT NOT NULL CHECK (source IN ('SYNTHETIC_LMS','MANUAL')),
  UNIQUE(enrollment_id, event_date)
);
CREATE TABLE AssessmentResult (
  result_id TEXT PRIMARY KEY,
  enrollment_id TEXT NOT NULL REFERENCES Enrollment(enrollment_id),
  assessment_id TEXT NOT NULL REFERENCES Assessment(assessment_id),
  score REAL CHECK (score BETWEEN 0 AND 100),
  status TEXT NOT NULL CHECK (status IN ('GRADED','MISSING','INCOMPLETE')),
  recorded_at TEXT NOT NULL,
  UNIQUE(enrollment_id, assessment_id),
  CHECK ((status = 'GRADED' AND score IS NOT NULL) OR (status <> 'GRADED' AND score IS NULL))
);
CREATE TABLE LMSActivity (
  activity_id TEXT PRIMARY KEY,
  enrollment_id TEXT NOT NULL REFERENCES Enrollment(enrollment_id),
  activity_at TEXT NOT NULL,
  activity_type TEXT NOT NULL CHECK (activity_type IN ('LOGIN','RESOURCE_VIEW','PRACTICE','DISCUSSION')),
  duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 1 AND 120)
);
CREATE TABLE RiskAssessment (
  risk_id TEXT PRIMARY KEY,
  enrollment_id TEXT NOT NULL UNIQUE REFERENCES Enrollment(enrollment_id),
  as_of_date TEXT NOT NULL,
  attendance_rate REAL,
  average_grade REAL,
  lms_events_per_week REAL,
  attendance_points INTEGER NOT NULL,
  grade_points INTEGER NOT NULL,
  engagement_points INTEGER NOT NULL,
  score INTEGER,
  decision TEXT NOT NULL CHECK (decision IN ('LOW','MEDIUM','HIGH','INSUFFICIENT_DATA','EXCLUDED')),
  explanation TEXT NOT NULL
);
CREATE TABLE SupportCase (
  case_id TEXT PRIMARY KEY,
  enrollment_id TEXT NOT NULL UNIQUE REFERENCES Enrollment(enrollment_id),
  opened_at TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('OPEN','IN_PROGRESS','CLOSED')),
  case_type TEXT NOT NULL CHECK (case_type IN ('ACADEMIC_OUTREACH','DATA_QUALITY_REVIEW')),
  trigger_risk_id TEXT NOT NULL REFERENCES RiskAssessment(risk_id)
);
CREATE TABLE Intervention (
  intervention_id TEXT PRIMARY KEY,
  case_id TEXT NOT NULL REFERENCES SupportCase(case_id),
  intervention_type TEXT NOT NULL CHECK (intervention_type IN ('TUTORING_REFERRAL','ADVISOR_OUTREACH','DATA_REVIEW')),
  assigned_role TEXT NOT NULL CHECK (assigned_role IN ('INSTRUCTOR','SUPPORT_STAFF','ACADEMIC_ADMIN')),
  scheduled_at TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('SCHEDULED','COMPLETED','DECLINED','NO_RESPONSE')),
  outcome TEXT CHECK (outcome IS NULL OR outcome IN ('COMPLETED','DECLINED','NO_RESPONSE'))
);
CREATE TABLE Outcome (
  outcome_id TEXT PRIMARY KEY,
  enrollment_id TEXT NOT NULL UNIQUE REFERENCES Enrollment(enrollment_id),
  observed_from TEXT NOT NULL,
  observed_to TEXT NOT NULL,
  future_attendance_rate REAL,
  future_average_grade REAL,
  label TEXT NOT NULL CHECK (label IN ('ON_TRACK','AT_RISK','INCOMPLETE')),
  label_rule TEXT NOT NULL
);
CREATE TABLE CaseEvent (
  case_event_id TEXT PRIMARY KEY,
  case_id TEXT NOT NULL REFERENCES SupportCase(case_id),
  event_at TEXT NOT NULL,
  from_status TEXT,
  to_status TEXT NOT NULL,
  event_type TEXT NOT NULL
);
CREATE TABLE InterventionEvent (
  intervention_event_id TEXT PRIMARY KEY,
  intervention_id TEXT NOT NULL REFERENCES Intervention(intervention_id),
  event_at TEXT NOT NULL,
  from_status TEXT,
  to_status TEXT NOT NULL,
  event_type TEXT NOT NULL
);
CREATE TABLE GroundTruth (
  record_id TEXT PRIMARY KEY,
  enrollment_id TEXT NOT NULL UNIQUE REFERENCES Enrollment(enrollment_id),
  outcome_label TEXT NOT NULL CHECK (outcome_label IN ('ON_TRACK','AT_RISK','INCOMPLETE')),
  expected_risk_band TEXT NOT NULL CHECK (expected_risk_band IN ('LOW','HIGH','INSUFFICIENT_DATA')),
  label_rule TEXT NOT NULL
);
