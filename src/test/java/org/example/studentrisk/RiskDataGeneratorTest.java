package org.example.studentrisk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskDataGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesLinkedTablesAndRepeatableOutputs() throws IOException {
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        RiskDataGenerator.Config firstConfig = new RiskDataGenerator.Config(first, 12, 6, 30, 20261022L);
        RiskDataGenerator.Config secondConfig = new RiskDataGenerator.Config(second, 12, 6, 30, 20261022L);

        RiskDataGenerator.Result result = RiskDataGenerator.generate(firstConfig);
        RiskDataGenerator.generate(secondConfig);

        assertEquals(12L, result.counts().get("students.csv"));
        assertEquals(6L, result.counts().get("courses.csv"));
        assertEquals(30L, result.counts().get("enrollments.csv"));
        assertEquals(30L, result.counts().get("risk_assessments.csv"));
        assertEquals(30L, result.counts().get("outcomes.csv"));
        assertEquals(30, readRows(first.resolve("ground_truth.csv")).size() - 1);
        assertEquals(5, readRows(first.resolve("edge_cases.csv")).size() - 1);
        assertTrue(Files.readString(first.resolve("validation_summary.json"))
                .contains("\"generation_checks_passed\": true"));
        assertTrue(Files.readString(first.resolve("evaluation_metrics.json")).contains("\"confusion_matrix\""));

        Set<String> files;
        try (var listing = Files.list(first)) {
            files = new HashSet<>(listing.map(path -> path.getFileName().toString()).toList());
        }
        for (String file : files) {
            assertTrue(Files.mismatch(first.resolve(file), second.resolve(file)) == -1,
                    "Expected deterministic file: " + file);
        }

        RiskDataGenerator.Result rerun = RiskDataGenerator.generate(firstConfig);
        assertEquals(result.checksums(), rerun.checksums());

        RiskDataGenerator.Result differentSeed = RiskDataGenerator.generate(
                new RiskDataGenerator.Config(temporaryDirectory.resolve("different-seed"), 12, 6, 30, 20261023L));
        assertNotEquals(result.checksums(), differentSeed.checksums());
    }

    @Test
    void defaultCommandLineRunProducesDataset() throws Exception {
        Path output = temporaryDirectory.resolve("cli-default");
        CliResult result = runCli("--output", output.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Generated 400 enrollments"));
        assertTrue(result.output().contains("Validation: passed"));
        assertEquals(100, readRows(output.resolve("students.csv")).size() - 1);
        assertTrue(Files.exists(output.resolve("validation_summary.json")));
    }

    @Test
    void cliScalesAndOverridesAreParsedAndValidated() {
        RiskDataGenerator.Config defaults = RiskDataGenerator.Config.parse(new String[0]);
        assertEquals(100, defaults.students());
        assertEquals(20, defaults.courses());
        assertEquals(400, defaults.enrollments());

        RiskDataGenerator.Config full = RiskDataGenerator.Config.parse(new String[]{"--full"});
        assertEquals(5_000, full.students());
        assertEquals(200, full.courses());
        assertEquals(20_000, full.enrollments());

        RiskDataGenerator.Config custom = RiskDataGenerator.Config.parse(new String[]{
                "--students", "2", "--courses", "3", "--enrollments", "6",
                "--full", "--seed", "-19", "--output", temporaryDirectory.toString()
        });
        assertEquals(2, custom.students());
        assertEquals(3, custom.courses());
        assertEquals(6, custom.enrollments());
        assertEquals(-19L, custom.seed());
        assertEquals(temporaryDirectory, custom.output());

        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.Config.parse(new String[]{"--unknown"}));
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.Config.parse(new String[]{"--students"}));
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.Config.parse(new String[]{"--courses"}));
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.Config.parse(new String[]{"--enrollments"}));
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.Config.parse(new String[]{"--seed"}));
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.Config.parse(new String[]{"--seed", "not-a-number"}));
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.Config.parse(new String[]{"--students", "2147483648"}));
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.Config.parse(new String[]{"--output", ""}));
    }

    @Test
    void invalidCliValuesReturnErrorsAndNonzeroExitCodes() throws Exception {
        CliResult unknown = runCli("--unknown");
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.output().contains("Invalid arguments: Unknown option: --unknown"));

        CliResult missing = runCli("--output");
        assertEquals(2, missing.exitCode());
        assertTrue(missing.output().contains("Missing value for --output"));

        CliResult nonNumeric = runCli("--students", "abc");
        assertEquals(2, nonNumeric.exitCode());
        assertTrue(nonNumeric.output().contains("--students must be a valid 32-bit integer"));

        CliResult negative = runCli("--students", "-1");
        assertEquals(2, negative.exitCode());
        assertTrue(negative.output().contains("must be positive"));

        CliResult zero = runCli("--courses", "0");
        assertEquals(2, zero.exitCode());
        assertTrue(zero.output().contains("must be positive"));

        CliResult oversized = runCli("--students", "100001");
        assertEquals(2, oversized.exitCode());
        assertTrue(oversized.output().contains("Maximum supported scale"));

        Path blocker = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(blocker, "file");
        CliResult invalidOutput = runCli("--output", blocker.resolve("child").toString());
        assertEquals(1, invalidOutput.exitCode());
        assertTrue(invalidOutput.output().contains("Generation failed:"));
    }

    @Test
    void generatedForeignKeysAndRiskCutoffAreValid() throws IOException {
        Path output = temporaryDirectory.resolve("linked");
        RiskDataGenerator.generate(new RiskDataGenerator.Config(output, 8, 4, 20, 91L));

        List<List<String>> students = readRows(output.resolve("students.csv"));
        List<List<String>> courses = readRows(output.resolve("courses.csv"));
        List<List<String>> enrollments = readRows(output.resolve("enrollments.csv"));
        List<List<String>> attendance = readRows(output.resolve("attendance_events.csv"));
        List<List<String>> assessments = readRows(output.resolve("assessments.csv"));
        List<List<String>> results = readRows(output.resolve("assessment_results.csv"));
        List<List<String>> lms = readRows(output.resolve("lms_activities.csv"));
        List<List<String>> risks = readRows(output.resolve("risk_assessments.csv"));
        List<List<String>> outcomes = readRows(output.resolve("outcomes.csv"));
        List<List<String>> interventions = readRows(output.resolve("interventions.csv"));

        Set<String> studentIds = columnValues(students, 0);
        Set<String> courseIds = columnValues(courses, 0);
        Set<String> enrollmentIds = columnValues(enrollments, 0);
        Set<String> assessmentIds = columnValues(assessments, 0);
        Map<String, String> dueDates = new HashMap<>();
        for (int i = 1; i < assessments.size(); i++) {
            dueDates.put(assessments.get(i).get(0), assessments.get(i).get(3));
        }
        assertEquals(enrollmentIds.size(), enrollments.size() - 1);
        for (int i = 1; i < enrollments.size(); i++) {
            List<String> row = enrollments.get(i);
            assertTrue(studentIds.contains(row.get(1)));
            assertTrue(courseIds.contains(row.get(2)));
            assertEquals("ENR-" + row.get(1) + "-" + row.get(2), row.get(0));
        }
        for (int i = 1; i < attendance.size(); i++) {
            List<String> row = attendance.get(i);
            assertTrue(enrollmentIds.contains(row.get(1)));
        }
        for (int i = 1; i < results.size(); i++) {
            List<String> row = results.get(i);
            assertTrue(enrollmentIds.contains(row.get(1)));
            assertTrue(assessmentIds.contains(row.get(2)));
        }
        Map<String, double[]> attendanceTotals = new HashMap<>();
        for (int i = 1; i < attendance.size(); i++) {
            List<String> row = attendance.get(i);
            if (row.get(2).compareTo("2026-03-29") <= 0
                    && (row.get(3).equals("PRESENT") || row.get(3).equals("ABSENT"))) {
                double[] totals = attendanceTotals.computeIfAbsent(row.get(1), ignored -> new double[2]);
                totals[1]++;
                if (row.get(3).equals("PRESENT")) {
                    totals[0]++;
                }
            }
        }
        Map<String, List<Double>> earlyGrades = new HashMap<>();
        for (int i = 1; i < results.size(); i++) {
            List<String> row = results.get(i);
            if (row.get(4).equals("GRADED") && dueDates.get(row.get(2)).compareTo("2026-03-29") <= 0) {
                earlyGrades.computeIfAbsent(row.get(1), ignored -> new ArrayList<>())
                        .add(Double.parseDouble(row.get(3)));
            }
        }
        Map<String, Integer> earlyActivityCounts = new HashMap<>();
        for (int i = 1; i < lms.size(); i++) {
            List<String> row = lms.get(i);
            if (row.get(2).substring(0, 10).compareTo("2026-03-29") <= 0) {
                earlyActivityCounts.merge(row.get(1), 1, Integer::sum);
            }
        }
        Map<String, String> outcomeLabels = new HashMap<>();
        for (int i = 1; i < outcomes.size(); i++) {
            outcomeLabels.put(outcomes.get(i).get(1), outcomes.get(i).get(6));
        }
        long truePositive = 0;
        long falsePositive = 0;
        long falseNegative = 0;
        long trueNegative = 0;
        long evaluated = 0;
        for (int i = 1; i < risks.size(); i++) {
            List<String> row = risks.get(i);
            String label = outcomeLabels.get(row.get(1));
            if (label.equals("INCOMPLETE") || row.get(10).equals("INSUFFICIENT_DATA")) {
                continue;
            }
            evaluated++;
            boolean predictedPositive = row.get(10).equals("HIGH") || row.get(10).equals("MEDIUM");
            boolean actualPositive = label.equals("AT_RISK");
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
        String metricReport = Files.readString(output.resolve("evaluation_metrics.json"));
        assertTrue(metricReport.contains("\"evaluated_enrollments\": " + evaluated));
        assertTrue(metricReport.contains("\"true_positive\": " + truePositive
                + ", \"false_positive\": " + falsePositive
                + ", \"false_negative\": " + falseNegative
                + ", \"true_negative\": " + trueNegative));
        assertTrue(interventions.size() > 1);
        assertTrue(interventions.get(0).contains("assigned_role"));
        for (int i = 1; i < risks.size(); i++) {
            List<String> row = risks.get(i);
            String enrollmentId = row.get(1);
            assertEquals("2026-03-29", row.get(2));
            assertTrue(Set.of("LOW", "MEDIUM", "HIGH", "INSUFFICIENT_DATA").contains(row.get(10)));
            double[] attendanceValues = attendanceTotals.getOrDefault(enrollmentId, new double[2]);
            String expectedAttendance = attendanceValues[1] == 0 ? ""
                    : format(attendanceValues[0] / attendanceValues[1]);
            assertEquals(expectedAttendance, row.get(3));
            List<Double> grades = earlyGrades.getOrDefault(enrollmentId, List.of());
            String expectedGrade = grades.isEmpty() ? "" : format(grades.stream()
                    .mapToDouble(Double::doubleValue).average().orElseThrow());
            assertEquals(expectedGrade, row.get(4));
            String expectedLmsRate = format((double) earlyActivityCounts.getOrDefault(enrollmentId, 0) / 12);
            assertEquals(expectedLmsRate, row.get(5));
        }
        assertFalse(readRows(output.resolve("risk_assessments.csv")).get(0).contains("outcome_label"));
        String sqlSeed = Files.readString(output.resolve("seed.sql"));
        assertTrue(sqlSeed.contains("INSERT INTO Student"));
        assertTrue(sqlSeed.contains("INSERT INTO AssessmentResult"));
        assertTrue(sqlSeed.startsWith("PRAGMA foreign_keys = ON;"));
        assertTrue(sqlSeed.trim().endsWith("COMMIT;"));
    }

    @Test
    void riskPointBoundariesAreExplicit() {
        assertEquals(45, RiskDataGenerator.attendancePoints(0.6999));
        assertEquals(30, RiskDataGenerator.attendancePoints(0.70));
        assertEquals(15, RiskDataGenerator.attendancePoints(0.80));
        assertEquals(0, RiskDataGenerator.attendancePoints(0.90));
        assertEquals(35, RiskDataGenerator.gradePoints(54.99));
        assertEquals(25, RiskDataGenerator.gradePoints(55));
        assertEquals(10, RiskDataGenerator.gradePoints(65));
        assertEquals(0, RiskDataGenerator.gradePoints(75));
        assertEquals(20, RiskDataGenerator.engagementPoints(0.99));
        assertEquals(10, RiskDataGenerator.engagementPoints(1));
        assertEquals(0, RiskDataGenerator.engagementPoints(2));
    }

    @Test
    void rejectsImpossibleScales() {
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.generate(new RiskDataGenerator.Config(
                        temporaryDirectory.resolve("invalid"), 2, 2, 5, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> RiskDataGenerator.generate(new RiskDataGenerator.Config(
                        temporaryDirectory.resolve("invalid"), 0, 2, 1, 1L)));
    }

    @Test
    void failedGenerationDoesNotLeaveAStaleCompletionReport() throws IOException {
        Path output = temporaryDirectory.resolve("failed-output");
        Files.createDirectories(output);
        Path report = output.resolve("validation_summary.json");
        Files.writeString(report, "{\"generation_checks_passed\":true}", StandardCharsets.UTF_8);
        Files.createDirectory(output.resolve("seed.sql"));

        assertThrows(IOException.class, () -> RiskDataGenerator.generate(
                new RiskDataGenerator.Config(output, 2, 2, 2, 1L)));
        assertFalse(Files.exists(report));
    }

    private static Set<String> columnValues(List<List<String>> rows, int index) {
        Set<String> values = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            values.add(rows.get(i).get(index));
        }
        return values;
    }

    private static List<List<String>> readRows(Path path) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            rows.add(parseCsvLine(line));
        }
        return rows;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static CliResult runCli(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(RiskDataGenerator.class.getName());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new CliResult(process.waitFor(), output);
    }

    private record CliResult(int exitCode, String output) {
    }
}
