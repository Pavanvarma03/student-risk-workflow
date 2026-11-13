package org.example.studentrisk;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

public final class RiskDataGenerator {
    static final long DEFAULT_SEED = 20261022L;
    static final int YEAR_WEEKS = 36;
    static final LocalDate ACADEMIC_START = LocalDate.of(2026, 1, 5);
    static final int RISK_CUTOFF_WEEK = 12;
    static final int MAX_STUDENTS = 100_000;
    static final int MAX_COURSES = 10_000;
    static final int MAX_ENROLLMENTS = 50_000;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final List<String> DEPARTMENTS =
            List.of("SCIENCE", "ARTS", "BUSINESS", "TECHNOLOGY", "GENERAL");
    private static final List<String> ACTIVITY_TYPES =
            List.of("LOGIN", "RESOURCE_VIEW", "PRACTICE", "DISCUSSION");
    private static final int[] ASSESSMENT_WEEKS = {4, 10, 22, 34};

    private RiskDataGenerator() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        try {
            Config config = Config.parse(args);
            Result result = generate(config);
            System.out.printf(Locale.ROOT, "Generated %d enrollments in %s%n",
                    result.counts().get("enrollments.csv"), config.output().toAbsolutePath());
            System.out.println("Validation: passed");
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid arguments: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("Generation failed: " + e.getMessage());
            return 1;
        }
    }

    static Result generate(Config config) throws IOException {
        validateConfig(config);
        Files.createDirectories(config.output());
        Files.deleteIfExists(config.output().resolve("validation_summary.json"));
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, CsvTable> tables = new LinkedHashMap<>();
        List<Student> studentRows = new ArrayList<>(config.students());
        List<Course> courseRows = new ArrayList<>(config.courses());
        List<Enrollment> enrollmentRows = new ArrayList<>(config.enrollments());
        List<GroundTruthRow> truthRows = new ArrayList<>(config.enrollments());
        EvaluationMetrics evaluation = new EvaluationMetrics();
        try (BufferedWriter sql = Files.newBufferedWriter(
                config.output().resolve("seed.sql"), StandardCharsets.UTF_8)) {
            sql.write("PRAGMA foreign_keys = ON;\nBEGIN;\n");
            CsvTable students = table(config, tables, counts, sql, "students.csv", "Student",
                    "student_id", "cohort_year", "created_at");
            CsvTable courses = table(config, tables, counts, sql, "courses.csv", "Course",
                    "course_id", "course_name", "department", "credits");
            CsvTable enrollments = table(config, tables, counts, sql, "enrollments.csv", "Enrollment",
                    "enrollment_id", "student_id", "course_id", "start_date", "end_date", "status", "issue_type");
            CsvTable assessments = table(config, tables, counts, sql, "assessments.csv", "Assessment",
                    "assessment_id", "course_id", "title", "due_date", "max_score", "sequence_no");
            CsvTable attendance = table(config, tables, counts, sql, "attendance_events.csv", "AttendanceEvent",
                    "attendance_id", "enrollment_id", "event_date", "status", "source");
            CsvTable results = table(config, tables, counts, sql, "assessment_results.csv", "AssessmentResult",
                    "result_id", "enrollment_id", "assessment_id", "score", "status", "recorded_at");
            CsvTable lms = table(config, tables, counts, sql, "lms_activities.csv", "LMSActivity",
                    "activity_id", "enrollment_id", "activity_at", "activity_type", "duration_minutes");
            CsvTable risks = table(config, tables, counts, sql, "risk_assessments.csv", "RiskAssessment",
                    "risk_id", "enrollment_id", "as_of_date", "attendance_rate", "average_grade",
                    "lms_events_per_week", "attendance_points", "grade_points", "engagement_points",
                    "score", "decision", "explanation");
            CsvTable cases = table(config, tables, counts, sql, "support_cases.csv", "SupportCase",
                    "case_id", "enrollment_id", "opened_at", "status", "case_type", "trigger_risk_id");
            CsvTable interventions = table(config, tables, counts, sql, "interventions.csv", "Intervention",
                    "intervention_id", "case_id", "intervention_type", "assigned_role",
                    "scheduled_at", "status", "outcome");
            CsvTable caseEvents = table(config, tables, counts, sql, "case_events.csv", "CaseEvent",
                    "case_event_id", "case_id", "event_at", "from_status", "to_status", "event_type");
            CsvTable interventionEvents = table(config, tables, counts, sql, "intervention_events.csv", "InterventionEvent",
                    "intervention_event_id", "intervention_id", "event_at", "from_status", "to_status", "event_type");
            CsvTable outcomes = table(config, tables, counts, sql, "outcomes.csv", "Outcome",
                    "outcome_id", "enrollment_id", "observed_from", "observed_to",
                    "future_attendance_rate", "future_average_grade", "label", "label_rule");
            CsvTable groundTruth = table(config, tables, counts, sql, "ground_truth.csv", "GroundTruth",
                    "record_id", "enrollment_id", "outcome_label", "expected_risk_band", "label_rule");

            for (int i = 1; i <= config.students(); i++) {
                Student student = new Student(studentId(i));
                studentRows.add(student);
                students.write(student.id(), "2026", ACADEMIC_START.toString());
            }

            for (int i = 1; i <= config.courses(); i++) {
                Course course = new Course(courseId(i), DEPARTMENTS.get((i - 1) % DEPARTMENTS.size()));
                courseRows.add(course);
                courses.write(course.id(), "Course " + String.format(Locale.ROOT, "%06d", i),
                        course.department(), Integer.toString(1 + ((i - 1) % 6)));
            }

            List<List<Assessment>> assessmentsByCourse = new ArrayList<>(config.courses());
            for (Course course : courseRows) {
                List<Assessment> courseAssessments = new ArrayList<>(ASSESSMENT_WEEKS.length);
                for (int i = 0; i < ASSESSMENT_WEEKS.length; i++) {
                    int week = ASSESSMENT_WEEKS[i];
                    Assessment assessment = new Assessment(
                            "ASM-" + course.id() + "-" + String.format(Locale.ROOT, "%02d", i + 1), week);
                    courseAssessments.add(assessment);
                    assessments.write(assessment.id(), course.id(), "Assessment " + (i + 1),
                            weekDate(week).toString(), "100", Integer.toString(i + 1));
                }
                assessmentsByCourse.add(courseAssessments);
            }

            int caseSequence = 0;
            int interventionSequence = 0;
            int riskSequence = 0;
            int caseEventSequence = 0;
            int interventionEventSequence = 0;

            for (int i = 0; i < config.enrollments(); i++) {
                int studentIndex = i / config.courses();
                int courseIndex = i % config.courses();
                Student student = studentRows.get(studentIndex);
                Course course = courseRows.get(courseIndex);
                String enrollmentId = "ENR-" + student.id() + "-" + course.id();
                Enrollment enrollment = makeEnrollment(enrollmentId, student, course, i, config.seed());
                enrollmentRows.add(enrollment);
                enrollments.write(enrollment.id(), student.id(), course.id(), ACADEMIC_START.toString(),
                        enrollment.endDate() == null ? "" : enrollment.endDate().toString(),
                        enrollment.status(), "");

                List<Assessment> courseAssessments = assessmentsByCourse.get(courseIndex);
                SplittableRandom random = new SplittableRandom(mix(config.seed(), i + 1L));
                double severity = severity(config.seed(), i + 1L);
                List<WeekSignal> signals = new ArrayList<>(YEAR_WEEKS);
                for (int week = 1; week <= YEAR_WEEKS; week++) {
                    LocalDate eventDate = weekDate(week);
                    if (enrollment.endDate() != null && eventDate.isAfter(enrollment.endDate())) {
                        break;
                    }
                    double draw = random.nextDouble();
                    String status;
                    if (draw < 0.025) {
                        status = "UNKNOWN";
                    } else if (draw < 0.075) {
                        status = "APPROVED_ABSENCE";
                    } else {
                        double presentProbability = Math.max(0.25, 0.98 - severity * 0.24);
                        status = random.nextDouble() < presentProbability ? "PRESENT" : "ABSENT";
                    }
                    String attendanceId = "ATT-" + enrollment.id() + "-" + eventDate.format(DateTimeFormatter.BASIC_ISO_DATE);
                    attendance.write(attendanceId, enrollment.id(), eventDate.toString(), status, "SYNTHETIC_LMS");
                    double activityRate = 0.25 + (1 - severity) * 1.75;
                    int activityCount = (int) activityRate;
                    if (random.nextDouble() < activityRate - activityCount) {
                        activityCount++;
                    }
                    for (int activityNo = 1; activityNo <= activityCount; activityNo++) {
                        String activityId = "LMS-" + enrollment.id() + "-"
                                + eventDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                                + String.format(Locale.ROOT, "%02d", activityNo);
                        String type = ACTIVITY_TYPES.get(random.nextInt(ACTIVITY_TYPES.size()));
                        int duration = 5 + random.nextInt(116);
                        lms.write(activityId, enrollment.id(),
                                eventDate.atTime(8 + random.nextInt(13), random.nextInt(60)).format(TIMESTAMP),
                                type, Integer.toString(duration));
                    }
                    signals.add(new WeekSignal(week, status, activityCount));
                }

                List<Double> earlyGrades = new ArrayList<>(2);
                List<Double> futureGrades = new ArrayList<>(2);
                for (Assessment assessment : courseAssessments) {
                    LocalDate dueDate = weekDate(assessment.dueWeek());
                    LocalDate recordedDate = dueDate.plusDays(2);
                    if (enrollment.endDate() != null && recordedDate.isAfter(enrollment.endDate())) {
                        continue;
                    }
                    boolean missing = random.nextDouble() < 0.035;
                    boolean incomplete = !missing && random.nextDouble() < 0.015;
                    double score = clamp(58 + (1 - severity) * 32 + random.nextGaussian() * 12, 0, 100);
                    String resultStatus = missing ? "MISSING" : incomplete ? "INCOMPLETE" : "GRADED";
                    String scoreText = resultStatus.equals("GRADED") ? decimal(score) : "";
                    results.write("RES-" + enrollment.id() + "-" + assessment.id(), enrollment.id(),
                            assessment.id(), scoreText, resultStatus, recordedDate.atTime(12, 0).format(TIMESTAMP));
                    if (resultStatus.equals("GRADED")) {
                        double storedScore = Double.parseDouble(scoreText);
                        if (assessment.dueWeek() <= RISK_CUTOFF_WEEK) {
                            earlyGrades.add(storedScore);
                        } else {
                            futureGrades.add(storedScore);
                        }
                    }
                }

                int currentRisk = ++riskSequence;
                String riskId = "RSK-2026-" + String.format(Locale.ROOT, "%06d", currentRisk);
                RiskFeatures features = features(signals, earlyGrades);
                risks.write(riskId, enrollment.id(), weekDate(RISK_CUTOFF_WEEK).plusDays(6).toString(),
                        optionalDecimal(features.attendanceRate()), optionalDecimal(features.averageGrade()),
                        optionalDecimal(features.lmsPerWeek()), Integer.toString(features.attendancePoints()),
                        Integer.toString(features.gradePoints()), Integer.toString(features.engagementPoints()),
                        features.score() == null ? "" : Integer.toString(features.score()),
                        features.decision(), features.explanation());

                LocalDate futureStart = weekDate(RISK_CUTOFF_WEEK + 1);
                FutureMetrics future = futureMetrics(signals, futureStart, futureGrades);
                String outcomeLabel = outcomeLabel(future);
                String outcomeRule = "From week 13 through enrollment end (no later than week 36): ON_TRACK requires attendance >= 0.75 and average grade >= 65; incomplete inputs remain INCOMPLETE.";
                LocalDate outcomeEnd = enrollment.endDate() == null
                        ? weekDate(YEAR_WEEKS).plusDays(6) : enrollment.endDate();
                outcomes.write("OUT-" + enrollment.id(), enrollment.id(), futureStart.toString(),
                        outcomeEnd.toString(), optionalDecimal(future.attendanceRate()),
                        optionalDecimal(future.averageGrade()), outcomeLabel, outcomeRule);
                String riskBand = outcomeLabel.equals("AT_RISK") ? "HIGH" : outcomeLabel.equals("ON_TRACK") ? "LOW" : "INSUFFICIENT_DATA";
                GroundTruthRow truth = new GroundTruthRow(enrollment.id(), outcomeLabel, riskBand, outcomeRule);
                truthRows.add(truth);
                groundTruth.write("GT-" + enrollment.id(), enrollment.id(), outcomeLabel, riskBand, outcomeRule);
                evaluation.recordDecision(features.decision(), outcomeLabel);

                boolean needsCase = features.decision().equals("HIGH")
                        || features.decision().equals("INSUFFICIENT_DATA");
                if (needsCase) {
                    int caseNo = ++caseSequence;
                    String caseId = "CASE-2026-" + String.format(Locale.ROOT, "%06d", caseNo);
                    LocalDateTime openedAt = weekDate(RISK_CUTOFF_WEEK).plusDays(7).atTime(9, 0);
                    String caseType = features.decision().equals("INSUFFICIENT_DATA")
                            ? "DATA_QUALITY_REVIEW" : "ACADEMIC_OUTREACH";
                    String interventionType = caseType.equals("DATA_QUALITY_REVIEW")
                            ? "DATA_REVIEW" : (caseNo % 2 == 0 ? "TUTORING_REFERRAL" : "ADVISOR_OUTREACH");
                    String assignedRole = interventionType.equals("TUTORING_REFERRAL") ? "INSTRUCTOR"
                            : interventionType.equals("ADVISOR_OUTREACH") ? "SUPPORT_STAFF" : "ACADEMIC_ADMIN";
                    String interventionStatus = terminalInterventionStatus(config.seed(), caseNo);
                    evaluation.recordIntervention(interventionStatus);
                    boolean closed = interventionStatus.equals("COMPLETED") || interventionStatus.equals("DECLINED");
                    String caseStatus = closed ? "CLOSED" : "IN_PROGRESS";
                    cases.write(caseId, enrollment.id(), openedAt.format(TIMESTAMP), caseStatus, caseType, riskId);
                    int caseEventNo = ++caseEventSequence;
                    caseEvents.write("CE-2026-" + String.format(Locale.ROOT, "%06d", caseEventNo),
                            caseId, openedAt.format(TIMESTAMP), "", "OPEN", "CASE_OPENED");
                    caseEventNo = ++caseEventSequence;
                    caseEvents.write("CE-2026-" + String.format(Locale.ROOT, "%06d", caseEventNo),
                            caseId, openedAt.plusDays(1).format(TIMESTAMP), "OPEN", "IN_PROGRESS", "REVIEW_STARTED");
                    if (closed) {
                        caseEventNo = ++caseEventSequence;
                        caseEvents.write("CE-2026-" + String.format(Locale.ROOT, "%06d", caseEventNo),
                                caseId, openedAt.plusDays(14).format(TIMESTAMP), "IN_PROGRESS", "CLOSED", "CASE_RESOLVED");
                    }

                    int interventionNo = ++interventionSequence;
                    String interventionId = "INT-2026-" + String.format(Locale.ROOT, "%06d", interventionNo);
                    LocalDateTime scheduledAt = openedAt.plusDays(2);
                    interventions.write(interventionId, caseId, interventionType, assignedRole,
                            scheduledAt.format(TIMESTAMP), interventionStatus, interventionStatus);
                    int interventionEventNo = ++interventionEventSequence;
                    interventionEvents.write("IE-2026-" + String.format(Locale.ROOT, "%06d", interventionEventNo),
                            interventionId, scheduledAt.format(TIMESTAMP), "", "SCHEDULED", "INTERVENTION_SCHEDULED");
                    interventionEventNo = ++interventionEventSequence;
                    interventionEvents.write("IE-2026-" + String.format(Locale.ROOT, "%06d", interventionEventNo),
                            interventionId, scheduledAt.plusDays(7).format(TIMESTAMP), "SCHEDULED",
                            interventionStatus, "INTERVENTION_OUTCOME_RECORDED");
                }
            }

            validateGenerated(config, studentRows, courseRows, enrollmentRows, truthRows, counts);
            sql.write("COMMIT;\n");
            for (CsvTable table : tables.values()) {
                table.close();
            }
        } finally {
            for (CsvTable table : tables.values()) {
                table.closeQuietly();
            }
        }

        writeEvaluationMetrics(config.output().resolve("evaluation_metrics.json"), evaluation);
        writeGroundTruthJson(config.output().resolve("ground_truth.json"), truthRows);
        writeEdgeCases(config.output().resolve("edge_cases.csv"), enrollmentRows);
        writeManifest(config, counts, config.output().resolve("source_manifest.json"));
        Map<String, String> checksums = checksums(config.output());
        writeSummary(config, counts, checksums, config.output().resolve("validation_summary.json"));
        return new Result(Map.copyOf(counts), Map.copyOf(checksums));
    }

    private static CsvTable table(Config config, Map<String, CsvTable> tables, Map<String, Long> counts,
                                  BufferedWriter sql, String filename, String sqlName, String... columns)
            throws IOException {
        CsvTable table = new CsvTable(config.output().resolve(filename), sql, sqlName, counts, filename, columns);
        tables.put(filename, table);
        counts.put(filename, 0L);
        return table;
    }

    private static Enrollment makeEnrollment(String id, Student student, Course course, int index, long seed) {
        double draw = unit(mix(seed ^ 0x4f1bbcdcL, index + 1L));
        if (draw < 0.025) {
            int week = 19 + (int) (unit(mix(seed, index + 900_001L)) * 14);
            return new Enrollment(id, student, course, "WITHDRAWN", weekDate(week));
        }
        if (draw < 0.045) {
            int week = 19 + (int) (unit(mix(seed, index + 800_001L)) * 14);
            return new Enrollment(id, student, course, "TRANSFERRED", weekDate(week));
        }
        if (draw < 0.20) {
            return new Enrollment(id, student, course, "COMPLETED", weekDate(YEAR_WEEKS));
        }
        return new Enrollment(id, student, course, "ACTIVE", null);
    }

    private static RiskFeatures features(List<WeekSignal> signals, List<Double> grades) {
        int present = 0;
        int eligible = 0;
        int lms = 0;
        for (WeekSignal signal : signals) {
            if (signal.week() > RISK_CUTOFF_WEEK) {
                break;
            }
            if (signal.status().equals("PRESENT") || signal.status().equals("ABSENT")) {
                eligible++;
                if (signal.status().equals("PRESENT")) {
                    present++;
                }
            }
            lms += signal.activityCount();
        }
        Double rate = eligible == 0 ? null : (double) present / eligible;
        Double average = average(grades);
        double perWeek = (double) lms / RISK_CUTOFF_WEEK;
        if (eligible < 4 || average == null) {
            return new RiskFeatures(rate, average, perWeek, 0, 0, engagementPoints(perWeek),
                    null, "INSUFFICIENT_DATA",
                    "Insufficient eligible attendance or graded assessment history; data review required.");
        }
        int attendancePoints = attendancePoints(rate);
        int gradePoints = gradePoints(average);
        int engagementPoints = engagementPoints(perWeek);
        int score = attendancePoints + gradePoints + engagementPoints;
        String decision = score >= 50 ? "HIGH" : score >= 25 ? "MEDIUM" : "LOW";
        String explanation = "Attendance " + decimal(rate) + " contributes " + attendancePoints
                + " points; early average grade " + decimal(average) + " contributes " + gradePoints
                + "; LMS events/week " + decimal(perWeek) + " contributes " + engagementPoints + ".";
        return new RiskFeatures(rate, average, perWeek, attendancePoints, gradePoints,
                engagementPoints, score, decision, explanation);
    }

    static int attendancePoints(double rate) {
        return rate < 0.70 ? 45 : rate < 0.80 ? 30 : rate < 0.90 ? 15 : 0;
    }

    static int gradePoints(double average) {
        return average < 55 ? 35 : average < 65 ? 25 : average < 75 ? 10 : 0;
    }

    static int engagementPoints(double eventsPerWeek) {
        return eventsPerWeek < 1 ? 20 : eventsPerWeek < 2 ? 10 : 0;
    }

    private static FutureMetrics futureMetrics(List<WeekSignal> signals, LocalDate start, List<Double> grades) {
        int present = 0;
        int eligible = 0;
        for (WeekSignal signal : signals) {
            if (!weekDate(signal.week()).isBefore(start)) {
                if (signal.status().equals("PRESENT") || signal.status().equals("ABSENT")) {
                    eligible++;
                    if (signal.status().equals("PRESENT")) {
                        present++;
                    }
                }
            }
        }
        return new FutureMetrics(eligible == 0 ? null : (double) present / eligible, average(grades));
    }

    private static String outcomeLabel(FutureMetrics metrics) {
        if (metrics.attendanceRate() == null || metrics.averageGrade() == null) {
            return "INCOMPLETE";
        }
        return metrics.attendanceRate() >= 0.75 && metrics.averageGrade() >= 65 ? "ON_TRACK" : "AT_RISK";
    }

    private static String terminalInterventionStatus(long seed, int sequence) {
        double draw = unit(mix(seed ^ 0x72a4d3L, sequence));
        return draw < 0.58 ? "COMPLETED" : draw < 0.77 ? "DECLINED" : "NO_RESPONSE";
    }

    private static void writeGroundTruthJson(Path path, List<GroundTruthRow> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("[\n");
            for (int i = 0; i < rows.size(); i++) {
                GroundTruthRow row = rows.get(i);
                writer.write("  {\"record_id\":\"GT-" + json(row.enrollmentId())
                        + "\",\"enrollment_id\":\"" + json(row.enrollmentId())
                        + "\",\"outcome_label\":\"" + json(row.outcomeLabel())
                        + "\",\"expected_risk_band\":\"" + json(row.expectedRiskBand())
                        + "\",\"label_rule\":\"" + json(row.labelRule()) + "\"}");
                writer.write(i + 1 == rows.size() ? "\n" : ",\n");
            }
            writer.write("]\n");
        }
    }

    private static void writeEvaluationMetrics(Path path, EvaluationMetrics metrics) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("{\n");
            writer.write("  \"positive_rule\": \"MEDIUM or HIGH risk decision versus AT_RISK future outcome; "
                    + "INCOMPLETE outcomes and INSUFFICIENT_DATA decisions are excluded.\",\n");
            writer.write("  \"evaluated_enrollments\": " + metrics.evaluated + ",\n");
            writer.write("  \"excluded_enrollments\": " + metrics.excluded + ",\n");
            writer.write("  \"confusion_matrix\": {\"true_positive\": " + metrics.truePositive
                    + ", \"false_positive\": " + metrics.falsePositive + ", \"false_negative\": "
                    + metrics.falseNegative + ", \"true_negative\": " + metrics.trueNegative + "},\n");
            writer.write("  \"accuracy\": " + ratio(metrics.truePositive + metrics.trueNegative, metrics.evaluated) + ",\n");
            writer.write("  \"precision\": " + ratio(metrics.truePositive, metrics.truePositive + metrics.falsePositive) + ",\n");
            writer.write("  \"recall\": " + ratio(metrics.truePositive, metrics.truePositive + metrics.falseNegative) + ",\n");
            writer.write("  \"f1\": " + f1(metrics.truePositive, metrics.falsePositive, metrics.falseNegative) + ",\n");
            writer.write("  \"intervention_outcomes\": {\"total\": " + metrics.interventions
                    + ", \"completed\": " + metrics.completed + ", \"declined\": " + metrics.declined
                    + ", \"no_response\": " + metrics.noResponse + ", \"completion_rate\": "
                    + ratio(metrics.completed, metrics.interventions) + "}\n");
            writer.write("}\n");
        }
    }

    private static String ratio(long numerator, long denominator) {
        return denominator == 0 ? "null" : decimal((double) numerator / denominator);
    }

    private static String f1(long truePositive, long falsePositive, long falseNegative) {
        long denominator = 2 * truePositive + falsePositive + falseNegative;
        return denominator == 0 ? "null" : decimal((double) (2 * truePositive) / denominator);
    }

    private static void writeEdgeCases(Path path, List<Enrollment> enrollments) throws IOException {
        Enrollment sample = enrollments.get(0);
        String[] issueTypes = {
                "FUTURE_GRADE_LEAKAGE",
                "WRONG_ATTENDANCE_DENOMINATOR",
                "WITHDRAWN_STUDENT_STILL_ACTIVE",
                "INTERVENTION_PRECEDES_TRIGGER",
                "MISSING_DATA_TREATED_AS_FAILURE"
        };
        String[] expected = {
                "Reject any risk feature dated after the week-12 cutoff.",
                "Exclude approved absences and unknown events from the attendance denominator.",
                "Represent the withdrawn lifecycle state and its effective end date; do not retain ACTIVE.",
                "Require intervention schedule time to be on or after the triggering risk timestamp.",
                "Route insufficient history to data review; do not assign a failing score."
        };
        String[] faulty = {
                "Includes week-22 assessment in a week-12 decision.",
                "Counts approved absences as failures in the attendance denominator.",
                "Status ACTIVE despite an end date and withdrawal event.",
                "Scheduled one day before the risk trigger.",
                "Converts a missing grade to score 0."
        };
        String[] clean = {
                "Risk assessment uses only results due on or before week 12.",
                "Rate = PRESENT / (PRESENT + ABSENT).",
                "WITHDRAWN with end date; no later events.",
                "Scheduled two days after the risk trigger.",
                "INSUFFICIENT_DATA with a DATA_QUALITY_REVIEW case."
        };
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(csv("issue_id", "issue_type", "entity_key", "expected_behavior", "faulty_example", "clean_value", "severity"));
            writer.newLine();
            for (int i = 0; i < issueTypes.length; i++) {
                writer.write(csv("ISSUE-" + String.format(Locale.ROOT, "%02d", i + 1), issueTypes[i],
                        sample.id(), expected[i], faulty[i], clean[i], i == 2 ? "HIGH" : "MEDIUM"));
                writer.newLine();
            }
        }
    }

    private static void writeManifest(Config config, Map<String, Long> counts, Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("{\n");
            writer.write("  \"generator\": \"student-risk-workflow 1.0.0\",\n");
            writer.write("  \"provenance\": \"Synthetic deterministic data only; no external or personal data.\",\n");
            writer.write("  \"seed\": " + config.seed() + ",\n");
            writer.write("  \"students\": " + config.students() + ",\n");
            writer.write("  \"courses\": " + config.courses() + ",\n");
            writer.write("  \"enrollments\": " + config.enrollments() + ",\n");
            writer.write("  \"risk_cutoff_week\": " + RISK_CUTOFF_WEEK + ",\n");
            writer.write("  \"counts\": {\n");
            int index = 0;
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                writer.write("    \"" + json(entry.getKey()) + "\": " + entry.getValue());
                writer.write(++index == counts.size() ? "\n" : ",\n");
            }
            writer.write("  }\n}\n");
        }
    }

    private static void writeSummary(Config config, Map<String, Long> counts, Map<String, String> checksums,
                                     Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("{\n  \"generation_checks_passed\": true,\n");
            writer.write("  \"seed\": " + config.seed() + ",\n");
            writer.write("  \"checks\": {\"student_course_enrollment_keys\": \"passed\", "
                    + "\"enrollment_parent_references\": \"passed\", "
                    + "\"enrollment_lifecycle_dates\": \"passed\", "
                    + "\"ground_truth_references\": \"passed\", "
                    + "\"one_to_one_table_counts\": \"passed\"},\n");
            writer.write("  \"counts\": {\n");
            writeJsonMap(writer, counts);
            writer.write("  },\n  \"sha256\": {\n");
            int index = 0;
            for (Map.Entry<String, String> entry : checksums.entrySet()) {
                writer.write("    \"" + json(entry.getKey()) + "\": \"" + entry.getValue() + "\"");
                writer.write(++index == checksums.size() ? "\n" : ",\n");
            }
            writer.write("  }\n}\n");
        }
    }

    private static void writeJsonMap(BufferedWriter writer, Map<String, Long> values) throws IOException {
        int index = 0;
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            writer.write("    \"" + json(entry.getKey()) + "\": " + entry.getValue());
            writer.write(++index == values.size() ? "\n" : ",\n");
        }
    }

    private static Map<String, String> checksums(Path output) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.list(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().equals("validation_summary.json")) {
                    result.put(file.getFileName().toString(), sha256(file));
                }
            }
        }
        return result;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static void validateConfig(Config config) {
        if (config.output() == null) {
            throw new IllegalArgumentException("output directory must be provided.");
        }
        if (config.students() < 1 || config.courses() < 1 || config.enrollments() < 1) {
            throw new IllegalArgumentException("students, courses, and enrollments must be positive.");
        }
        if (config.students() > MAX_STUDENTS || config.courses() > MAX_COURSES
                || config.enrollments() > MAX_ENROLLMENTS) {
            throw new IllegalArgumentException("Maximum supported scale is "
                    + MAX_STUDENTS + " students, " + MAX_COURSES + " courses, and "
                    + MAX_ENROLLMENTS + " enrollments.");
        }
        if ((long) config.students() * config.courses() < config.enrollments()) {
            throw new IllegalArgumentException("enrollments cannot exceed students multiplied by courses.");
        }
    }

    private static void validateGenerated(Config config, List<Student> students, List<Course> courses,
                                          List<Enrollment> enrollments, List<GroundTruthRow> truth,
                                          Map<String, Long> counts) {
        Set<String> studentIds = new HashSet<>();
        for (Student student : students) {
            if (!studentIds.add(student.id())) {
                throw new IllegalStateException("Duplicate student primary key: " + student.id());
            }
        }
        Set<String> courseIds = new HashSet<>();
        for (Course course : courses) {
            if (!courseIds.add(course.id())) {
                throw new IllegalStateException("Duplicate course primary key: " + course.id());
            }
        }
        Set<String> enrollmentIds = new HashSet<>();
        Set<String> enrollmentPairs = new HashSet<>();
        for (Enrollment enrollment : enrollments) {
            if (!studentIds.contains(enrollment.student().id()) || !courseIds.contains(enrollment.course().id())) {
                throw new IllegalStateException("Enrollment references an unknown parent: " + enrollment.id());
            }
            if (!enrollmentIds.add(enrollment.id())
                    || !enrollmentPairs.add(enrollment.student().id() + "|" + enrollment.course().id())) {
                throw new IllegalStateException("Duplicate enrollment key: " + enrollment.id());
            }
            if (enrollment.status().equals("ACTIVE") != (enrollment.endDate() == null)
                    || (enrollment.endDate() != null && enrollment.endDate().isBefore(weekDate(RISK_CUTOFF_WEEK)))) {
                throw new IllegalStateException("Invalid enrollment lifecycle dates: " + enrollment.id());
            }
        }
        Set<String> truthEnrollmentIds = new HashSet<>();
        for (GroundTruthRow row : truth) {
            if (!enrollmentIds.contains(row.enrollmentId()) || !truthEnrollmentIds.add(row.enrollmentId())) {
                throw new IllegalStateException("Ground truth has an invalid or duplicate enrollment.");
            }
        }
        if (students.size() != config.students() || courses.size() != config.courses()
                || enrollments.size() != config.enrollments() || truthEnrollmentIds.size() != enrollments.size()
                || !counts.get("risk_assessments.csv").equals((long) enrollments.size())
                || !counts.get("outcomes.csv").equals((long) enrollments.size())
                || !counts.get("ground_truth.csv").equals((long) enrollments.size())
                || !counts.get("support_cases.csv").equals(counts.get("interventions.csv"))) {
            throw new IllegalStateException("Generated table counts or one-to-one relationships did not reconcile.");
        }
        for (long count : counts.values()) {
            if (count < 0) {
                throw new IllegalStateException("A generated table has a negative row count.");
            }
        }
    }

    static LocalDate weekDate(int week) {
        return ACADEMIC_START.plusWeeks(week - 1L);
    }

    private static String studentId(int index) {
        return "STU-2026-" + String.format(Locale.ROOT, "%06d", index);
    }

    private static String courseId(int index) {
        return "CRS-" + String.format(Locale.ROOT, "%06d", index);
    }

    private static double severity(long seed, long index) {
        return unit(mix(seed ^ 0x2d997fL, index));
    }

    private static long mix(long seed, long value) {
        long z = seed + 0x9E3779B97F4A7C15L * value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }

    private static Double average(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String optionalDecimal(Double value) {
        return value == null ? "" : decimal(value);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String csv(String... fields) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            String value = fields[i] == null ? "" : fields[i];
            builder.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return builder.toString();
    }

    private static String sqlValue(String value) {
        if (value == null || value.isEmpty()) {
            return "NULL";
        }
        if (value.matches("-?\\d+(\\.\\d+)?")) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private record Student(String id) {
    }

    private record Course(String id, String department) {
    }

    private record Assessment(String id, int dueWeek) {
    }

    private record Enrollment(String id, Student student, Course course, String status, LocalDate endDate) {
    }

    private record WeekSignal(int week, String status, int activityCount) {
    }

    private record RiskFeatures(Double attendanceRate, Double averageGrade, Double lmsPerWeek,
                                int attendancePoints, int gradePoints, int engagementPoints,
                                Integer score, String decision, String explanation) {
    }

    private record FutureMetrics(Double attendanceRate, Double averageGrade) {
    }

    private record GroundTruthRow(String enrollmentId, String outcomeLabel, String expectedRiskBand,
                                  String labelRule) {
    }

    private static final class EvaluationMetrics {
        private long evaluated;
        private long excluded;
        private long truePositive;
        private long falsePositive;
        private long falseNegative;
        private long trueNegative;
        private long interventions;
        private long completed;
        private long declined;
        private long noResponse;

        private void recordDecision(String decision, String outcome) {
            if (outcome.equals("INCOMPLETE")
                    || decision.equals("INSUFFICIENT_DATA") || decision.equals("EXCLUDED")) {
                excluded++;
                return;
            }
            evaluated++;
            boolean predictedPositive = decision.equals("MEDIUM") || decision.equals("HIGH");
            boolean actualPositive = outcome.equals("AT_RISK");
            if (predictedPositive && actualPositive) {
                truePositive++;
            } else if (predictedPositive) {
                falsePositive++;
            } else if (actualPositive) {
                falseNegative++;
            } else {
                trueNegative++;
            }
        }

        private void recordIntervention(String status) {
            interventions++;
            switch (status) {
                case "COMPLETED" -> completed++;
                case "DECLINED" -> declined++;
                case "NO_RESPONSE" -> noResponse++;
                default -> throw new IllegalArgumentException("Unexpected intervention outcome: " + status);
            }
        }
    }

    record Result(Map<String, Long> counts, Map<String, String> checksums) {
    }

    record Config(Path output, int students, int courses, int enrollments, long seed) {
        static Config parse(String[] args) {
            Path output = Path.of("sample-output");
            Integer studentOverride = null;
            Integer courseOverride = null;
            Integer enrollmentOverride = null;
            long seed = DEFAULT_SEED;
            boolean full = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--full" -> full = true;
                    case "--output" -> {
                        String value = requireValue(args, ++i, "--output");
                        if (value.isBlank()) {
                            throw new IllegalArgumentException("--output must not be empty.");
                        }
                        output = Path.of(value);
                    }
                    case "--seed" -> seed = parseLong(requireValue(args, ++i, "--seed"), "--seed");
                    case "--students" -> studentOverride =
                            parseInteger(requireValue(args, ++i, "--students"), "--students");
                    case "--courses" -> courseOverride =
                            parseInteger(requireValue(args, ++i, "--courses"), "--courses");
                    case "--enrollments" -> enrollmentOverride =
                            parseInteger(requireValue(args, ++i, "--enrollments"), "--enrollments");
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            int students = studentOverride != null ? studentOverride : full ? 5_000 : 100;
            int courses = courseOverride != null ? courseOverride : full ? 200 : 20;
            int enrollments = enrollmentOverride != null ? enrollmentOverride : full ? 20_000 : 400;
            return new Config(output, students, courses, enrollments, seed);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static int parseInteger(String value, String option) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " must be a valid 32-bit integer.");
            }
        }

        private static long parseLong(String value, String option) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " must be a valid 64-bit integer.");
            }
        }
    }

    private static final class CsvTable {
        private final BufferedWriter csv;
        private final BufferedWriter sql;
        private final String tableName;
        private final Map<String, Long> counts;
        private final String countKey;
        private final String[] columns;
        private long count;
        private boolean closed;

        private CsvTable(Path path, BufferedWriter sql, String tableName, Map<String, Long> counts,
                         String countKey, String... columns) throws IOException {
            this.csv = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            this.sql = sql;
            this.tableName = tableName;
            this.counts = counts;
            this.countKey = countKey;
            this.columns = Arrays.copyOf(columns, columns.length);
            csv.write(csv(columns));
            csv.newLine();
        }

        private void write(String... values) throws IOException {
            if (values.length != columns.length) {
                throw new IllegalArgumentException("Column count mismatch in " + tableName);
            }
            csv.write(csv(values));
            csv.newLine();
            sql.write("INSERT INTO " + tableName + " (" + String.join(", ", columns)
                    + ") VALUES (" + String.join(", ", Arrays.stream(values)
                    .map(RiskDataGenerator::sqlValue).toList()) + ");\n");
            count++;
            counts.put(countKey, count);
        }

        private void close() throws IOException {
            if (!closed) {
                csv.close();
                closed = true;
            }
        }

        private void closeQuietly() {
            if (!closed) {
                try {
                    close();
                } catch (IOException ignored) {
                    // Closing remaining table streams is best-effort after the original failure.
                }
            }
        }
    }
}
