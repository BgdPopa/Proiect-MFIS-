package eu.fbk.iv4xr.mbt.fifo;

import eu.fbk.iv4xr.mbt.efsm.EFSM;
import eu.fbk.iv4xr.mbt.efsm.EFSMState;
import eu.fbk.iv4xr.mbt.efsm.EFSMTransition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;

/**
 * Runner de experiment MBT pentru modelul FIFO.
 *
 * Ce face:
 * 1) genereaza suite de teste abstracte din EFSM;
 * 2) calculeaza metrici de coverage (stari, tranzitii, perechi de tranzitii);
 * 3) ruleaza un oracle care verifica semantica FIFO;
 * 4) compara MBT cu un baseline simplu random/manual;
 * 5) exporta rezultate in CSV + Markdown pentru prezentare.
 */
public class FifoExperimentRunner {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int DEFAULT_RUNS = 30;
    private static final int DEFAULT_TESTS_PER_RUN = 20;
    private static final int DEFAULT_MAX_STEPS_PER_TEST = 15;

    private static class GeneratedTestCase {
        // Traseul abstract executat de model.
        List<String> transitions = new ArrayList<>();
        List<String> visitedStates = new ArrayList<>();
    }

    private static class RunStats {
        // Seed-ul random pentru reproducibilitate.
        int seed;
        // Marime suita generata.
        int generatedTests;
        int generatedSteps;
        double avgTestLength;
        // Distributia actiunilor.
        int pushCount;
        int popCount;
        // Metrici principale de coverage.
        double transitionCoverage;
        double stateCoverage;
        double transitionPairCoverage;
        // Cazuri in care oracle-ul detecteaza defectul din SUT-ul buggy.
        int testsDetectingBug;
        int oracleChecks;
        int oracleFailuresOnCorrectSut;
        // Curba de convergenta a coverage-ului pe pasi.
        List<Double> transitionCoverageConvergence = new ArrayList<>();
        Set<String> coveredTransitions = new HashSet<>();
        Set<String> coveredStates = new HashSet<>();
        Set<String> coveredTransitionPairs = new HashSet<>();
        List<GeneratedTestCase> suite = new ArrayList<>();
    }

    private enum Action {
        PUSH,
        POP
    }

    /**
     * Interfata comuna pentru implementari SUT.
     * Ne permite sa comparam acelasi test pe varianta corecta vs buggy.
     */
    private interface QueueSut {
        boolean enqueue(int value);
        Integer dequeue();
    }

    // Implementarea corecta FIFO, folosita ca referinta in oracle.
    private static class CorrectFifoSut implements QueueSut {
        private final int capacity;
        private final Deque<Integer> queue = new ArrayDeque<>();

        private CorrectFifoSut(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public boolean enqueue(int value) {
            if (queue.size() >= capacity) {
                return false;
            }
            queue.addLast(value);
            return true;
        }

        @Override
        public Integer dequeue() {
            if (queue.isEmpty()) {
                return null;
            }
            return queue.removeFirst();
        }
    }

    /**
     * SUT cu bug deliberat: scoate ultimul element (LIFO), nu primul (FIFO).
     * Oracle-ul pe ordine trebuie sa detecteze comportamentul gresit.
     */
    private static class BuggyFifoSut implements QueueSut {
        private final int capacity;
        private final Deque<Integer> queue = new ArrayDeque<>();

        private BuggyFifoSut(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public boolean enqueue(int value) {
            if (queue.size() >= capacity) {
                return false;
            }
            queue.addLast(value);
            return true;
        }

        @Override
        public Integer dequeue() {
            if (queue.isEmpty()) {
                return null;
            }
            return queue.removeLast();
        }
    }

    public static void main(String[] args) throws IOException {
        int runs = DEFAULT_RUNS;
        int testsPerRun = DEFAULT_TESTS_PER_RUN;
        int maxStepsPerTest = DEFAULT_MAX_STEPS_PER_TEST;

        FifoQueueModel modelTemplate = new FifoQueueModel();
        EFSM templateEfsm = modelTemplate.buildModel();
        int totalTransitions = templateEfsm.getTransitons().size();
        int totalStates = templateEfsm.getStates().size();
        Set<String> allTransitionPairs = computeAllTransitionPairs(templateEfsm);

        List<RunStats> allRuns = new ArrayList<>();
        List<Double> manualCoverages = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            int seed = 100 + i;
            // Rulare MBT (model-guided).
            allRuns.add(executeRun(seed, testsPerRun, maxStepsPerTest, totalTransitions, totalStates, allTransitionPairs));
            // Baseline simplu (alegeri random), pentru comparatie in raport.
            manualCoverages.add(runManualRandomBaseline(seed + 10_000, testsPerRun, maxStepsPerTest, totalTransitions));
        }

        Path outDir = Paths.get("results");
        Files.createDirectories(outDir);
        String timestamp = LocalDateTime.now().format(TS_FORMAT);
        writeCsv(outDir.resolve("fifo_experiment_" + timestamp + ".csv"), allRuns, manualCoverages);
        writeSummary(
                outDir.resolve("fifo_experiment_summary_" + timestamp + ".md"),
                allRuns,
                manualCoverages,
                runs,
                testsPerRun,
                maxStepsPerTest
        );

        System.out.println("Experiment finalizat.");
        System.out.println("Rulari: " + runs);
        System.out.println("Teste per rulare: " + testsPerRun);
        System.out.println("Pasi maximi per test: " + maxStepsPerTest);
        System.out.println("Rezultate scrise in folderul results/.");
    }

    /**
     * Executa o rulare completa:
     * - genereaza testele;
     * - colecteaza metrici;
     * - ruleaza oracle pe fiecare test.
     */
    private static RunStats executeRun(
            int seed,
            int testsPerRun,
            int maxStepsPerTest,
            int totalTransitions,
            int totalStates,
            Set<String> allTransitionPairs
    ) {
        FifoQueueModel fifoQueueModel = new FifoQueueModel();
        EFSM model = fifoQueueModel.buildModel();
        Random random = new Random(seed);

        RunStats stats = new RunStats();
        stats.seed = seed;
        stats.coveredStates.add(model.getInitialConfiguration().getState().getId());

        for (int t = 0; t < testsPerRun; t++) {
            model.reset();
            GeneratedTestCase testCase = new GeneratedTestCase();
            testCase.visitedStates.add(model.getConfiguration().getState().getId());
            String previousTransitionId = null;

            for (int step = 0; step < maxStepsPerTest; step++) {
                EFSMTransition chosen = pickFeasibleTransition(model, random);
                if (chosen == null) {
                    // Nu mai exista tranzitie fezabila din starea curenta.
                    break;
                }

                model.transition(chosen);
                String transitionId = chosen.getId();
                String stateId = model.getConfiguration().getState().getId();

                testCase.transitions.add(transitionId);
                testCase.visitedStates.add(stateId);

                stats.coveredTransitions.add(transitionId);
                stats.coveredStates.add(stateId);
                if (previousTransitionId != null) {
                    // Pair coverage: perechi de tranzitii consecutive.
                    stats.coveredTransitionPairs.add(previousTransitionId + "->" + transitionId);
                }
                previousTransitionId = transitionId;
                stats.generatedSteps++;
                if (transitionId.startsWith("push")) {
                    stats.pushCount++;
                } else if (transitionId.startsWith("pop")) {
                    stats.popCount++;
                }
                stats.transitionCoverageConvergence.add(100.0 * stats.coveredTransitions.size() / totalTransitions);
            }

            stats.generatedTests++;
            stats.suite.add(testCase);

            OracleResult oracleResult = executeOracleChecks(testCase, fifoQueueModel.maxCapacity());
            stats.oracleChecks += oracleResult.totalChecks;
            stats.oracleFailuresOnCorrectSut += oracleResult.correctSutFailures;
            if (oracleResult.bugDetected) {
                // Numaram testele care surprind defectul introdus deliberat.
                stats.testsDetectingBug++;
            }
        }

        stats.transitionCoverage = totalTransitions == 0 ? 0.0 : (100.0 * stats.coveredTransitions.size() / totalTransitions);
        stats.stateCoverage = totalStates == 0 ? 0.0 : (100.0 * stats.coveredStates.size() / totalStates);
        stats.transitionPairCoverage = allTransitionPairs.isEmpty()
                ? 0.0
                : (100.0 * stats.coveredTransitionPairs.size() / allTransitionPairs.size());
        stats.avgTestLength = stats.generatedTests == 0 ? 0.0 : ((double) stats.generatedSteps / stats.generatedTests);
        return stats;
    }

    /**
     * Selecteaza o tranzitie fezabila din starea curenta.
     * Returneaza null daca modelul este blocat.
     */
    private static EFSMTransition pickFeasibleTransition(EFSM model, Random random) {
        EFSMState currentState = model.getConfiguration().getState();
        List<EFSMTransition> feasible = new ArrayList<>();
        for (EFSMTransition transition : model.transitionsOutOf(currentState)) {
            if (transition.isFeasible(model.getConfiguration().getContext())) {
                feasible.add(transition);
            }
        }
        if (feasible.isEmpty()) {
            return null;
        }
        // Sortare pentru rezultate reproductibile intre rulari.
        feasible.sort(Comparator.comparing(EFSMTransition::getId));
        return feasible.get(random.nextInt(feasible.size()));
    }

    /**
     * Calculeaza universul perechilor de tranzitii consecutive posibile.
     * Este denominator-ul pentru metrica Transition Pair Coverage.
     */
    private static Set<String> computeAllTransitionPairs(EFSM model) {
        // Multimea tuturor perechilor posibile T1->T2 din graful EFSM.
        Set<String> pairs = new HashSet<>();
        for (EFSMTransition t1 : model.getTransitons()) {
            EFSMState mid = t1.getTgt();
            for (EFSMTransition t2 : model.transitionsOutOf(mid)) {
                pairs.add(t1.getId() + "->" + t2.getId());
            }
        }
        return pairs;
    }

    /**
     * Ruleaza oracle-ul pentru un test abstract:
     * - mentine o coada-oracol (comportament asteptat);
     * - executa aceleasi actiuni pe SUT corect + SUT buggy;
     * - raporteaza mismatch-uri.
     */
    private static OracleResult executeOracleChecks(GeneratedTestCase testCase, int maxCapacity) {
        QueueSut correct = new CorrectFifoSut(maxCapacity);
        QueueSut buggy = new BuggyFifoSut(maxCapacity);
        Deque<Integer> oracleQueue = new ArrayDeque<>();
        int nextValue = 1;
        int totalChecks = 0;
        int correctFailures = 0;
        boolean bugDetected = false;

        for (String transitionId : testCase.transitions) {
            Action action = transitionId.startsWith("push") ? Action.PUSH : Action.POP;
            if (action == Action.PUSH) {
                int value = nextValue++;
                boolean expected = oracleQueue.size() < maxCapacity;
                if (expected) {
                    oracleQueue.addLast(value);
                }
                boolean okCorrect = correct.enqueue(value);
                boolean okBuggy = buggy.enqueue(value);
                totalChecks += 2;
                if (okCorrect != expected) {
                    correctFailures++;
                }
                if (okBuggy != expected) {
                    bugDetected = true;
                }
            } else {
                // Verificam explicit ordinea FIFO a valorilor extrase.
                Integer expectedValue = oracleQueue.pollFirst();
                Integer valueCorrect = correct.dequeue();
                Integer valueBuggy = buggy.dequeue();
                totalChecks += 2;
                if (!equalsNullable(expectedValue, valueCorrect)) {
                    correctFailures++;
                }
                if (!equalsNullable(expectedValue, valueBuggy)) {
                    bugDetected = true;
                }
            }
        }
        return new OracleResult(totalChecks, correctFailures, bugDetected);
    }

    /**
     * Utilitar pentru comparatii Integer care pot fi null.
     */
    private static boolean equalsNullable(Integer a, Integer b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    /**
     * Rezumatul unei rulari de oracle pe un test.
     */
    private static class OracleResult {
        private final int totalChecks;
        private final int correctSutFailures;
        private final boolean bugDetected;

        private OracleResult(int totalChecks, int correctSutFailures, boolean bugDetected) {
            this.totalChecks = totalChecks;
            this.correctSutFailures = correctSutFailures;
            this.bugDetected = bugDetected;
        }
    }

    /**
     * Baseline simplu random/manual:
     * nu foloseste direct EFSM-ul pentru alegerea tranzitiilor, doar regula de marime.
     */
    private static double runManualRandomBaseline(int seed, int testsPerRun, int maxStepsPerTest, int totalTransitions) {
        Random random = new Random(seed);
        int size = 0;
        int maxCapacity = new FifoQueueModel().maxCapacity();
        Set<String> coveredTransitions = new HashSet<>();

        for (int t = 0; t < testsPerRun; t++) {
            size = 0;
            for (int step = 0; step < maxStepsPerTest; step++) {
                boolean doPush = random.nextBoolean();
                String transition = null;
                if (doPush && size < maxCapacity) {
                    if (size == 0) {
                        transition = "push_empty_to_partial";
                    } else if (size == maxCapacity - 1) {
                        transition = "push_partial_to_full";
                    } else {
                        transition = "push_partial_to_partial";
                    }
                    size++;
                } else if (!doPush && size > 0) {
                    if (size == 1) {
                        transition = "pop_partial_to_empty";
                    } else if (size == maxCapacity) {
                        transition = "pop_full_to_partial";
                    } else {
                        transition = "pop_partial_to_partial";
                    }
                    size--;
                }
                if (transition != null) {
                    coveredTransitions.add(transition);
                }
            }
        }
        if (totalTransitions == 0) {
            return 0.0;
        }
        return 100.0 * coveredTransitions.size() / totalTransitions;
    }

    /**
     * Export detaliat per rulare, bun pentru grafice externe.
     */
    private static void writeCsv(Path path, List<RunStats> runs, List<Double> manualCoverages) throws IOException {
        // CSV per rulare: util pentru grafice/tabele in prezentare.
        List<String> lines = new ArrayList<>();
        lines.add("seed,tests,steps,avg_test_len,push,pop,transition_coverage,state_coverage,pair_coverage,bug_detecting_tests,oracle_failures_correct_sut,manual_transition_coverage");
        for (int i = 0; i < runs.size(); i++) {
            RunStats r = runs.get(i);
            lines.add(String.format(
                    Locale.US,
                    "%d,%d,%d,%.2f,%d,%d,%.2f,%.2f,%.2f,%d,%d,%.2f",
                    r.seed,
                    r.generatedTests,
                    r.generatedSteps,
                    r.avgTestLength,
                    r.pushCount,
                    r.popCount,
                    r.transitionCoverage,
                    r.stateCoverage,
                    r.transitionPairCoverage,
                    r.testsDetectingBug,
                    r.oracleFailuresOnCorrectSut,
                    manualCoverages.get(i)
            ));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /**
     * Export sinteza agregata in Markdown, folosita in raport/prezentare.
     */
    private static void writeSummary(
            Path path,
            List<RunStats> runs,
            List<Double> manualCoverages,
            int runsCount,
            int testsPerRun,
            int maxSteps
    ) throws IOException {
        double avgTransitionCoverage = runs.stream().mapToDouble(r -> r.transitionCoverage).average().orElse(0.0);
        double stdTransitionCoverage = stddev(runs.stream().map(r -> r.transitionCoverage).collect(Collectors.toList()));
        double avgStateCoverage = runs.stream().mapToDouble(r -> r.stateCoverage).average().orElse(0.0);
        double avgPairCoverage = runs.stream().mapToDouble(r -> r.transitionPairCoverage).average().orElse(0.0);
        double avgSteps = runs.stream().mapToInt(r -> r.generatedSteps).average().orElse(0.0);
        double stdSteps = stddev(runs.stream().map(r -> (double) r.generatedSteps).collect(Collectors.toList()));
        double avgPush = runs.stream().mapToInt(r -> r.pushCount).average().orElse(0.0);
        double avgPop = runs.stream().mapToInt(r -> r.popCount).average().orElse(0.0);
        double avgBugDetections = runs.stream().mapToInt(r -> r.testsDetectingBug).average().orElse(0.0);
        double avgManualCoverage = manualCoverages.stream().mapToDouble(d -> d).average().orElse(0.0);
        List<Double> convergence = averageConvergence(runs);

        List<String> summary = new ArrayList<>();
        summary.add("# Analiza rezultate FIFO (MBT + Oracle extins)");
        summary.add("");
        summary.add("## Configuratie experiment");
        summary.add("- rulari: " + runsCount);
        summary.add("- teste per rulare: " + testsPerRun);
        summary.add("- pasi maximi per test: " + maxSteps);
        summary.add("- model EFSM: stari EMPTY, PARTIAL, FULL");
        summary.add("- oracle: verifica capacitate si ordinea FIFO a elementelor");
        summary.add("");
        summary.add("## Rezultate agregate");
        summary.add(String.format("- acoperire medie tranzitii: %.2f%% (std: %.2f)", avgTransitionCoverage, stdTransitionCoverage));
        summary.add(String.format("- acoperire medie stari: %.2f%%", avgStateCoverage));
        summary.add(String.format("- acoperire medie perechi tranzitii: %.2f%%", avgPairCoverage));
        summary.add(String.format("- pasi medii executati per rulare: %.2f", avgSteps));
        summary.add(String.format("- deviatia standard a pasilor/rulare: %.2f", stdSteps));
        summary.add(String.format("- tranzitii medii PUSH per rulare: %.2f", avgPush));
        summary.add(String.format("- tranzitii medii POP per rulare: %.2f", avgPop));
        summary.add(String.format("- teste medii care detecteaza SUT-ul buggy: %.2f / rulare", avgBugDetections));
        summary.add(String.format("- acoperire medie baseline manual/random: %.2f%%", avgManualCoverage));
        summary.add(String.format("- diferenta MBT - manual/random: %.2f pp", avgTransitionCoverage - avgManualCoverage));
        summary.add("");
        summary.add("## Convergenta acoperirii tranzitiilor");
        if (convergence.isEmpty()) {
            summary.add("- nu exista date de convergenta");
        } else {
            summary.add("- pasi agregati si acoperire medie:");
            for (int i = 0; i < convergence.size(); i++) {
                summary.add(String.format("  - pas %d -> %.2f%%", i + 1, convergence.get(i)));
            }
        }
        summary.add("");
        summary.add("## Verificare oracle");
        int totalOracleChecks = runs.stream().mapToInt(r -> r.oracleChecks).sum();
        int totalCorrectFailures = runs.stream().mapToInt(r -> r.oracleFailuresOnCorrectSut).sum();
        summary.add("- verificari totale oracle: " + totalOracleChecks);
        summary.add("- esecuri pe SUT corect (ar trebui 0): " + totalCorrectFailures);
        summary.add("");
        summary.add("## Concluzie");
        summary.add("MBT-ul FIFO este demonstrativ: produce suite abstracte, masoara coverage extins si valideaza corectitudinea FIFO cu oracle pe ordine.");
        summary.add("SUT-ul cu defect intentionat este detectat automat, ceea ce sustine argumentul de prezentare.");

        Files.write(path, summary, StandardCharsets.UTF_8);
    }

    /**
     * Deviatie standard (populatie) pentru lista de valori.
     */
    private static double stddev(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(v -> v).average().orElse(0.0);
        double variance = 0.0;
        for (double value : values) {
            double diff = value - mean;
            variance += diff * diff;
        }
        variance /= values.size();
        return Math.sqrt(variance);
    }

    /**
     * Media punct-cu-punct pentru curbele de convergenta.
     */
    private static List<Double> averageConvergence(List<RunStats> runs) {
        // Media punct-cu-punct a curbelor de convergenta.
        List<Double> result = new ArrayList<>();
        int maxLen = 0;
        for (RunStats run : runs) {
            maxLen = Math.max(maxLen, run.transitionCoverageConvergence.size());
        }
        for (int i = 0; i < maxLen; i++) {
            double sum = 0.0;
            int cnt = 0;
            for (RunStats run : runs) {
                if (i < run.transitionCoverageConvergence.size()) {
                    sum += run.transitionCoverageConvergence.get(i);
                    cnt++;
                }
            }
            result.add(cnt == 0 ? 0.0 : sum / cnt);
        }
        return result;
    }
}
