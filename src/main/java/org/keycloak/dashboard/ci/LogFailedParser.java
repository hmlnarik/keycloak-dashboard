package org.keycloak.dashboard.ci;

import org.keycloak.dashboard.util.DateUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LogFailedParser {

    final Pattern TEST_CASE_PATTERN = Pattern.compile("([\\w]*\\.[\\w]*)");

    static final Set<String> IGNORED_JOBS = new HashSet<>();
    static {
        IGNORED_JOBS.add("Set check conclusion");
        IGNORED_JOBS.add("Status Check - Keycloak CI");
    };

    private List<FailedRun> failedRuns = new LinkedList<>();

    private List<FailedRun> resolvedRuns = new LinkedList<>();

    public static void main(String[] args) throws IOException, ParseException {
        LogFailedParser parser = new LogFailedParser();

        parser.parseAll();

        parser.print();
    }

    public List<FailedJob> getRecentFailedJobs() {
        Date fromDate = DateUtil.MINUS_7_DAYS;
        List<FailedJob> recentFailedJobs = new LinkedList<>();
        for (FailedRun run : failedRuns) {
            if (run.getDate().after(fromDate)) {
                recentFailedJobs.addAll(run.getFailedJobs());
            }
        }
        recentFailedJobs.sort((j1, j2) -> j2.getFailedRun().getDate().compareTo(j1.getFailedRun().getDate()));
        return recentFailedJobs;
    }

    public Map<String, List<FailedJob>> getFailedTests() {
        Map<String, List<FailedJob>> failedTests = new TreeMap<>();
        for (FailedRun run : failedRuns) {
            for (FailedJob job : run.getFailedJobs()) {
                for (String failedTest : job.getFailedTests()) {
                    if (!failedTests.containsKey(failedTest)) {
                        failedTests.put(failedTest, new LinkedList<>());
                    }
                    failedTests.get(failedTest).add(job);
                }
            }
        }
        return failedTests;
    }

    public List<FailedRun> getFailedRuns() {
        return failedRuns;
    }

    public List<FailedRun> getResolvedRuns() {
        return resolvedRuns;
    }

    public Map<String, List<FailedJob>> getFailedJobs() {
        Map<String, List<FailedJob>> failedJobs = new TreeMap<>();
        for (FailedRun run : failedRuns) {
            for (FailedJob job : run.getFailedJobs()) {
                if (!failedJobs.containsKey(job.getJobName())) {
                    failedJobs.put(job.getJobName(), new LinkedList<>());
                }
                failedJobs.get(job.getJobName()).add(job);
            }
        }
        return failedJobs;
    }

    public void parseAll() throws IOException, ParseException {
        Set<String> excludedRuns = new HashSet<>();
        File excludedRunsFile = new File("resolved-runs");
        if (excludedRunsFile.isFile()) {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("resolved-runs")));
            for (String l = br.readLine(); l != null; l = br.readLine()) {
                if (!l.startsWith("#")) {
                    excludedRuns.add(l.trim());
                }
            }
        }

        List<String> runs = Arrays.stream(new File("logs").listFiles(file -> file.getName().startsWith("jobs-")))
                .map(file -> file.getName().replaceAll("jobs-", ""))
                .collect(Collectors.toList());
        for (String run : runs) {
            parse(run);
        }

        filterResolved();
    }

    public void filterResolved() throws IOException {
        File resolvedRunsFile = new File("resolved-runs");
        Map<String, List<String>> resolvedRuns = new HashMap<>();
        if (!resolvedRunsFile.isFile()) {
            return;
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("resolved-runs")));
        for (String l = br.readLine(); l != null; l = br.readLine()) {
            l = l.trim();
            if (!l.startsWith("#") && !l.isBlank()) {
                String[] split = l.split("/");
                String runId = split[0];
                String jobName = split[1];
                if (!resolvedRuns.containsKey(runId)) {
                    resolvedRuns.put(runId, new LinkedList<>());
                }
                resolvedRuns.get(runId).add(jobName);
            }
        }

        Iterator<FailedRun> runItr = failedRuns.iterator();
        while (runItr.hasNext()) {
            FailedRun failedRun = runItr.next();
            List<String> resolvedJobs = resolvedRuns.get(failedRun.getRunId());
            if (resolvedJobs != null && !resolvedJobs.isEmpty()) {
                FailedRun resolvedRun = new FailedRun(failedRun.getRunId());
                resolvedRun.setDate(failedRun.getDate());
                this.resolvedRuns.add(resolvedRun);

                if (resolvedJobs.contains("*")) {
                    runItr.remove();
                    System.out.println("Marking run as fully resolved: " + failedRun.getRunId());
                } else {
                    Iterator<FailedJob> jobItr = failedRun.getFailedJobs().iterator();
                    while (jobItr.hasNext()) {
                        FailedJob job = jobItr.next();
                        if (resolvedJobs.contains(job.getName())) {
                            resolvedRun.add(job);
                            jobItr.remove();
                            System.out.println("Found resolved job: " + resolvedRun.getRunId() + "/" + job.getName());
                        }
                    }

                    if (failedRun.getFailedJobs().isEmpty()) {
                        System.out.println("Removing empty run: " + failedRun.getRunId());
                        runItr.remove();
                    }
                }
            }
        }
    }

    public void parse(String runId) throws IOException, ParseException {
        File conclusionFile = new File("logs/jobs-" + runId);
        File logFile = new File("logs/log-" + runId);

        FailedRun failedRun = new FailedRun(runId);
        failedRuns.add(failedRun);

        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(conclusionFile)));
        List<FailedJob> jobs = new LinkedList<>();

        String jsonDate = br.readLine().substring(2);
        Date date = DateUtil.fromJson(jsonDate);
        failedRun.setDate(date);

        for (String l = br.readLine(); l != null; l = br.readLine()) {
            String[] split = l.split(": ");
            String name = split[0];
            JobConclusion conclusion = JobConclusion.fromLog(split[1]);
            if (conclusion != JobConclusion.SUCCESS && conclusion != JobConclusion.SKIPPED && !IGNORED_JOBS.contains(name)) {
                jobs.add(new FailedJob(failedRun, name, conclusion));
            }
        }

        for (FailedJob job : jobs) {
            if (job.getConclusion() == JobConclusion.FAILURE) {
                if (job.getName().equals("Store Model Tests")) {
                    failedRun.addAll(parseModelTest(failedRun, job, logFile));
                } else {
                    failedRun.add(parseTest(job, logFile));
                }
            } else {
                failedRun.add(job);
            }
        }

        failedRuns.sort(Comparator.comparing(FailedRun::getDate).reversed());
    }

    public void print() {
        System.out.println("==============================================================================================================");
        System.out.println("Failed tests");
        System.out.println();
        failedRuns.stream()
                .map(FailedRun::getFailedJobs).flatMap(Collection::stream)
                .map(FailedJob::getFailedTests).flatMap(Collection::stream)
                .sorted()
                .collect(Collectors.toMap(Function.identity(), s -> 1, Math::addExact)).forEach((s, c) -> System.out.println(s + "\t" + c));
        System.out.println("==============================================================================================================");

        for (FailedRun failedRun : failedRuns) {
            System.out.println("==============================================================================================================");
            System.out.println("https://github.com/keycloak/keycloak/actions/runs/" + failedRun.getRunId());

            for (FailedJob job : failedRun.getFailedJobs()) {
                System.out.println("--------------------------------------------------------------------------------------------------------------");
                System.out.println(job.getName() + " - " + job.getConclusion());
                if (!job.getErrorLog().isEmpty()) {
                    System.out.println("--------------------------------------------------------------------------------------------------------------");
                    System.out.println(job.getFailedGoal());
                    System.out.println();
                    for (String l : job.getErrorLog()) {
                        System.out.println(l);
                    }
                }
            }
        }

    }

    private FailedJob parseTest(FailedJob job, File logFile) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
        List<String> errorLines = new LinkedList<>();
        for (String l = br.readLine(); l != null; l = br.readLine()) {
            if (l.startsWith(job.getName()) && l.contains(" [ERROR] ")) {
                String[] split = l.split("\\[ERROR] ");
                if (split.length == 2) {
                    errorLines.add(split[1]);
                }
            }
        }
        parse(job, errorLines);
        return job;
    }


    private List<FailedJob> parseModelTest(FailedRun failedRun, FailedJob job, File logFile) throws IOException {
        List<FailedJob> failedJobs = new LinkedList<>();

        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
        List<String> errorLines = null;

        FailedJob failedJob = null;
        for (String l = br.readLine(); l != null; l = br.readLine()) {
            if (l.contains("======== Start of Profile")) {
                String profile = l.split("======== Start of Profile")[1];
                failedJob = new FailedJob(failedRun, job.getName() + " (" + profile.trim() + ")", job.getConclusion());
                errorLines = new LinkedList<>();
            } else if (l.contains("======== End of Profile")) {
                if (!errorLines.isEmpty()) {
                    parse(failedJob, errorLines);
                    failedJobs.add(failedJob);
                    failedJob = null;
                }
            } else if (failedJob != null && l.startsWith(job.getName()) && l.contains(" [ERROR] ")) {
                String[] split = l.split("\\[ERROR] ");
                if (split.length == 2) {
                    errorLines.add(split[1]);
                }
            }
        }

        return failedJobs;
    }

    private void parse(FailedJob job, List<String> errorLines) {
        for (String l : errorLines) {
            if (l.startsWith("Errors:")) {
                job.addErrorLog(l);
            } else if (l.startsWith("Failures:")) {
                job.addErrorLog(l);
            } else if (l.startsWith("  ")) {
                job.addErrorLog(l);
                Matcher m = TEST_CASE_PATTERN.matcher(l);
                if (m.find()) {
                    job.addFailedTests(m.group(1));
                }
            } else if (l.startsWith("Failed to execute goal")) {
                job.setFailedGoal(l);
            }
        }
    }

}
