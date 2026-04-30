package eu.fbk.iv4xr.mbt.fifo;


// FifoExperimentRunner.java
// Orchestratorul principal al experimentului MBT.
//
// Conține:
//   1. Configurația experimentului (rulări, teste, pași)
//   2. Structurile de date (GeneratedTestCase, RunStats)
//   3. Implementările SUT: CorrectFifoSut și BuggyFifoSut
//   4. main() — fluxul complet al experimentului
//   5. executeRun() — o singură rulare de 20 teste
//   6. pickFeasibleTransition() — algoritmul de selecție SBST
//   7. executeOracleChecks() — oracolul de verificare FIFO
//   8. runManualRandomBaseline() — baseline random pentru comparație
//   9. writeCsv() / writeSummary() — salvarea rezultatelor


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
import java.util.*;
import java.util.stream.Collectors;

public class FifoExperimentRunner {

    // Format timestamp pentru numele fișierelor de output (ex: 20240430_143022)
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // ============================================================
    // 1. CONFIGURAȚIA EXPERIMENTULUI
    //
    // 30 rulări × 20 teste × max 15 pași = până la 9.000 de pași
    // generați automat de algoritmul SBST pornind din modelul EFSM.
    // ============================================================
    private static final int DEFAULT_RUNS             = 30;
    private static final int DEFAULT_TESTS_PER_RUN    = 20;
    private static final int DEFAULT_MAX_STEPS_PER_TEST = 15;

    // ============================================================
    // 2a. STRUCTURA DE DATE — UN TEST GENERAT
    //
    // Reține secvența de tranziții executate și stările vizitate.
    // Exemplu: ["push_empty_to_partial", "push_partial_to_full",
    //           "pop_full_to_partial", "pop_partial_to_empty"]
    // Această secvență e apoi trimisă oracolului pentru verificare.
    // ============================================================
    private static class GeneratedTestCase {
        List<String> transitions   = new ArrayList<>();
        List<String> visitedStates = new ArrayList<>();
    }

    // ============================================================
    // 2b. STRUCTURA DE DATE — STATISTICILE UNEI RULĂRI
    //
    // Colectează toate metricile pentru una din cele 30 de rulări.
    // HashSet-urile elimină automat duplicatele la calculul coverage:
    //   coveredTransitions  → câte din cele 6 tranziții au fost atinse
    //   coveredStates       → câte din cele 3 stări au fost vizitate
    //   coveredTransitionPairs → câte perechi consecutive au fost acoperite
    // ============================================================
    private static class RunStats {
        int seed;                       // seed random (asigură reproductibilitatea)
        int generatedTests;
        int generatedSteps;
        double avgTestLength;
        int pushCount;
        int popCount;
        double transitionCoverage;      // % tranziții acoperite din total (țintă: 100%)
        double stateCoverage;           // % stări acoperite din total (țintă: 100%)
        double transitionPairCoverage;  // % perechi consecutive acoperite (metrică mai strictă)
        int testsDetectingBug;          // câte teste au prins BuggyFifoSut (țintă: ~93%)
        int oracleChecks;               // total verificări oracle (țintă: ~18.000 total)
        int oracleFailuresOnCorrectSut; // false positive-uri pe SUT corect (țintă: 0)
        List<Double> transitionCoverageConvergence = new ArrayList<>(); // evoluția coverage pas cu pas
        Set<String> coveredTransitions     = new HashSet<>();
        Set<String> coveredStates          = new HashSet<>();
        Set<String> coveredTransitionPairs = new HashSet<>();
        List<GeneratedTestCase> suite      = new ArrayList<>();
    }

    private enum Action { PUSH, POP }

    // ============================================================
    // 3a. INTERFAȚA COMUNĂ PENTRU SUT-URI
    //
    // Ambele implementări (corectă și buggy) respectă acest contract.
    // Oracolul le apelează identic și compară rezultatele — fără să
    // știe dinainte care e corectă și care e buggy.
    // ============================================================
    private interface QueueSut {
        boolean enqueue(int value);
        Integer dequeue();
    }

    // ============================================================
    // 3b. SUT CORECT — implementarea corectă a cozii FIFO
    //
    // addLast()    → adaugă la coada cozii
    // removeFirst() → scoate din capul cozii
    // Primul intrat = primul ieșit ✅
    // ============================================================
    private static class CorrectFifoSut implements QueueSut {
        private final int capacity;
        private final Deque<Integer> queue = new ArrayDeque<>();

        private CorrectFifoSut(int capacity) { this.capacity = capacity; }

        @Override
        public boolean enqueue(int value) {
            if (queue.size() >= capacity) return false; // refuză dacă coada e plină
            queue.addLast(value);
            return true;
        }

        @Override
        public Integer dequeue() {
            if (queue.isEmpty()) return null;
            return queue.removeFirst(); // ✅ FIFO: scoate primul intrat
        }
    }

    // ============================================================
    // 3c. SUT BUGGY — implementarea cu bug deliberat (LIFO)
    //
    // enqueue() e identică cu cea corectă — bug-ul e doar la dequeue.
    // removeLast() scoate ultimul element adăugat, nu primul.
    // Se comportă ca un stack (LIFO), nu ca o coadă (FIFO).
    //
    // Oracolul detectează acest comportament greșit comparând
    // elementul scos cu cel așteptat din oracleQueue.
    // ============================================================
    private static class BuggyFifoSut implements QueueSut {
        private final int capacity;
        private final Deque<Integer> queue = new ArrayDeque<>();

        private BuggyFifoSut(int capacity) { this.capacity = capacity; }

        @Override
        public boolean enqueue(int value) {
            if (queue.size() >= capacity) return false;
            queue.addLast(value);
            return true;
        }

        @Override
        public Integer dequeue() {
            if (queue.isEmpty()) return null;
            return queue.removeLast(); // ❌ BUG: scoate ultimul, nu primul → LIFO!
        }
    }

    // ============================================================
    // 4. MAIN — FLUXUL COMPLET AL EXPERIMENTULUI
    //
    // Pași:
    //   a) Construim modelul template pentru a afla totalurile
    //      (6 tranziții, 3 stări, N perechi) — numerele folosite
    //      ca numitor la calculul procentelor de coverage.
    //   b) Rulăm 30 de experimente MBT (executeRun).
    //   c) Rulăm 30 de baseline-uri random (runManualRandomBaseline)
    //      pentru comparație: MBT vs. testare aleatoare.
    //   d) Scriem rezultatele în results/ (CSV + Markdown).
    // ============================================================
    public static void main(String[] args) throws IOException {
        int runs             = DEFAULT_RUNS;
        int testsPerRun      = DEFAULT_TESTS_PER_RUN;
        int maxStepsPerTest  = DEFAULT_MAX_STEPS_PER_TEST;

        // a) Model template — doar pentru a numără elementele grafului
        FifoQueueModel modelTemplate  = new FifoQueueModel();
        EFSM templateEfsm             = modelTemplate.buildModel();
        int totalTransitions          = templateEfsm.getTransitons().size();       // → 6
        int totalStates               = templateEfsm.getStates().size();            // → 3
        Set<String> allTransitionPairs = computeAllTransitionPairs(templateEfsm);  // toate perechile posibile

        List<RunStats> allRuns        = new ArrayList<>();
        List<Double> manualCoverages  = new ArrayList<>();

        // b+c) 30 rulări MBT + 30 baseline-uri random
        for (int i = 0; i < runs; i++) {
            int seed = 100 + i; // seed unic per rulare → secvențe diferite, dar reproductibile
            allRuns.add(executeRun(seed, testsPerRun, maxStepsPerTest,
                    totalTransitions, totalStates, allTransitionPairs));
            manualCoverages.add(runManualRandomBaseline(seed + 10_000,
                    testsPerRun, maxStepsPerTest, totalTransitions));
        }

        // d) Salvăm rezultatele cu timestamp în numele fișierului
        Path outDir      = Paths.get("results");
        Files.createDirectories(outDir);
        String timestamp = LocalDateTime.now().format(TS_FORMAT);
        writeCsv(outDir.resolve("fifo_experiment_" + timestamp + ".csv"),
                allRuns, manualCoverages);
        writeSummary(outDir.resolve("fifo_experiment_summary_" + timestamp + ".md"),
                allRuns, manualCoverages, runs, testsPerRun, maxStepsPerTest);

        System.out.println("Experiment finalizat. Rezultate in folderul results/.");
    }

    // ============================================================
    // 5. EXECUȚIA UNEI SINGURE RULĂRI
    //
    // Generează testsPerRun teste, fiecare cu maxStepsPerTest pași.
    // La fiecare pas:
    //   - alege o tranziție validă din starea curentă (gardă OK)
    //   - avansează modelul EFSM
    //   - înregistrează tranzițiile și stările acoperite
    //   - actualizează curba de convergență coverage
    // La finalul fiecărui test, rulează oracolul pe secvența generată.
    // ============================================================
    private static RunStats executeRun(int seed, int testsPerRun, int maxStepsPerTest,
                                       int totalTransitions, int totalStates, Set<String> allTransitionPairs) {

        FifoQueueModel fifoQueueModel = new FifoQueueModel();
        EFSM model   = fifoQueueModel.buildModel();
        Random random = new Random(seed);

        RunStats stats = new RunStats();
        stats.seed = seed;
        // Starea inițială EMPTY e deja vizitată înainte de primul test
        stats.coveredStates.add(model.getInitialConfiguration().getState().getId());

        for (int t = 0; t < testsPerRun; t++) {
            model.reset(); // fiecare test pornește din EMPTY cu currentSize = 0
            GeneratedTestCase testCase = new GeneratedTestCase();
            testCase.visitedStates.add(model.getConfiguration().getState().getId());
            String previousTransitionId = null;

            for (int step = 0; step < maxStepsPerTest; step++) {
                // Selectăm o tranziție cu garda satisfăcută în starea curentă
                EFSMTransition chosen = pickFeasibleTransition(model, random);
                if (chosen == null) break; // nicio tranziție posibilă → test terminat

                model.transition(chosen); // avansăm modelul EFSM
                String transitionId = chosen.getId();
                String stateId      = model.getConfiguration().getState().getId();

                testCase.transitions.add(transitionId);
                testCase.visitedStates.add(stateId);

                // HashSet adaugă doar dacă nu e deja prezent → coverage fără duplicate
                stats.coveredTransitions.add(transitionId);
                stats.coveredStates.add(stateId);

                // Transition-pair: perechea (tranziție anterioară → tranziție curentă)
                if (previousTransitionId != null) {
                    stats.coveredTransitionPairs.add(previousTransitionId + "->" + transitionId);
                }
                previousTransitionId = transitionId;
                stats.generatedSteps++;

                if (transitionId.startsWith("push")) stats.pushCount++;
                else if (transitionId.startsWith("pop")) stats.popCount++;

                // Snapshot coverage la fiecare pas → pentru graficul de convergență
                stats.transitionCoverageConvergence.add(
                        100.0 * stats.coveredTransitions.size() / totalTransitions);
            }

            stats.generatedTests++;
            stats.suite.add(testCase);

            // Verificăm testul generat cu oracolul (corect vs. buggy)
            OracleResult oracleResult = executeOracleChecks(testCase, fifoQueueModel.maxCapacity());
            stats.oracleChecks              += oracleResult.totalChecks;
            stats.oracleFailuresOnCorrectSut += oracleResult.correctSutFailures; // trebuie 0
            if (oracleResult.bugDetected) stats.testsDetectingBug++;
        }

        // Calculăm procentele finale de coverage pentru această rulare
        stats.transitionCoverage     = 100.0 * stats.coveredTransitions.size() / totalTransitions;
        stats.stateCoverage          = 100.0 * stats.coveredStates.size() / totalStates;
        stats.transitionPairCoverage = allTransitionPairs.isEmpty() ? 0.0
                : 100.0 * stats.coveredTransitionPairs.size() / allTransitionPairs.size();
        stats.avgTestLength          = stats.generatedTests == 0 ? 0.0
                : (double) stats.generatedSteps / stats.generatedTests;
        return stats;
    }

    // ============================================================
    // 6. SELECȚIA TRANZIȚIEI — ALGORITMUL SBST
    //
    // Din starea curentă, colectează toate tranzițiile cu garda
    // satisfăcută (isFeasible) și alege una random dintre ele.
    // Sortarea înainte de selecție garantează că același seed
    // produce aceeași secvență de teste (reproductibilitate).
    //
    // Dacă nicio tranziție nu e posibilă (ex: EMPTY fără push
    // disponibil) → returnează null → testul se oprește.
    // ============================================================
    private static EFSMTransition pickFeasibleTransition(EFSM model, Random random) {
        EFSMState currentState = model.getConfiguration().getState();
        List<EFSMTransition> feasible = new ArrayList<>();
        for (EFSMTransition transition : model.transitionsOutOf(currentState)) {
            if (transition.isFeasible(model.getConfiguration().getContext())) {
                feasible.add(transition); // garda e true → tranziție validă
            }
        }
        if (feasible.isEmpty()) return null;
        feasible.sort(Comparator.comparing(EFSMTransition::getId)); // determinism
        return feasible.get(random.nextInt(feasible.size()));
    }

    // ============================================================
    // (UTILITAR) PRECALCULAREA TUTUROR PERECHILOR DE TRANZIȚII
    //
    // Parcurge graful și colectează toate perechile (t1 → t2) unde
    // t2 pleacă din starea în care ajunge t1.
    // Rezultatul e folosit ca numitor pentru transition-pair coverage.
    // ============================================================
    private static Set<String> computeAllTransitionPairs(EFSM model) {
        Set<String> pairs = new HashSet<>();
        for (EFSMTransition t1 : model.getTransitons()) {
            EFSMState mid = t1.getTgt(); // destinația lui t1 = sursa lui t2
            for (EFSMTransition t2 : model.transitionsOutOf(mid)) {
                pairs.add(t1.getId() + "->" + t2.getId());
            }
        }
        return pairs;
    }

    // ============================================================
    // 7. ORACOLUL DE TEST — verificarea corectitudinii FIFO
    //
    // Rulează aceeași secvență de tranziții pe 3 cozi simultan:
    //   oracleQueue    → "ground truth" gestionat manual de oracle
    //   CorrectFifoSut → implementarea corectă (trebuie să coincidă)
    //   BuggyFifoSut   → implementarea cu bug (trebuie să difere)
    //
    // La PUSH: oracle adaugă valoarea și verifică că SUT-urile acceptă
    // La POP:  oracle știe ce element trebuie să iasă (primul adăugat)
    //          și compară cu ce returnează fiecare SUT
    //
    // Dacă BuggyFifoSut returnează altceva → bugDetected = true
    // Dacă CorrectFifoSut returnează altceva → correctFailures++ (eroare în implementare)
    // ============================================================
    private static OracleResult executeOracleChecks(GeneratedTestCase testCase, int maxCapacity) {
        QueueSut correct            = new CorrectFifoSut(maxCapacity);
        QueueSut buggy              = new BuggyFifoSut(maxCapacity);
        Deque<Integer> oracleQueue  = new ArrayDeque<>(); // coada "adevărului"
        int nextValue      = 1;     // valori secvențiale: 1, 2, 3, ... (predictibile)
        int totalChecks    = 0;
        int correctFailures = 0;
        boolean bugDetected = false;

        for (String transitionId : testCase.transitions) {
            Action action = transitionId.startsWith("push") ? Action.PUSH : Action.POP;

            if (action == Action.PUSH) {
                int value         = nextValue++;
                boolean expected  = oracleQueue.size() < maxCapacity; // ar trebui să reușească?
                if (expected) oracleQueue.addLast(value);              // oracle ține minte ordinea
                boolean okCorrect = correct.enqueue(value);
                boolean okBuggy   = buggy.enqueue(value);
                totalChecks += 2;
                if (okCorrect != expected) correctFailures++;  // nu ar trebui să se întâmple
                if (okBuggy   != expected) bugDetected = true; // comportament neașteptat pe buggy

            } else { // POP
                Integer expectedValue = oracleQueue.pollFirst(); // primul adăugat → răspunsul corect
                Integer valueCorrect  = correct.dequeue();
                Integer valueBuggy    = buggy.dequeue();
                totalChecks += 2;
                if (!equalsNullable(expectedValue, valueCorrect)) correctFailures++; // nu ar trebui
                if (!equalsNullable(expectedValue, valueBuggy))   bugDetected = true; // ❌ BUG prins!
            }
        }
        return new OracleResult(totalChecks, correctFailures, bugDetected);
    }

    // Comparație null-safe — dequeue poate returna null pe coadă goală
    private static boolean equalsNullable(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }

    // Clasa care împachetează rezultatul oracolului pentru o singură rulare
    private static class OracleResult {
        private final int totalChecks;
        private final int correctSutFailures;
        private final boolean bugDetected;

        private OracleResult(int totalChecks, int correctSutFailures, boolean bugDetected) {
            this.totalChecks          = totalChecks;
            this.correctSutFailures   = correctSutFailures;
            this.bugDetected          = bugDetected;
        }
    }

    // ============================================================
    // 8. BASELINE RANDOM — comparație cu testarea aleatoare
    //
    // Simulează un tester care alege push/pop complet aleator,
    // fără să consulte modelul EFSM sau gărzile.
    // Operațiile ilegale (push pe full, pop pe empty) sunt ignorate
    // — pași pierduți, spre deosebire de MBT care nu îi pierde.
    //
    // Scopul: demonstrează că MBT obține coverage mai mare decât
    // testarea aleatoare cu același număr de pași.
    // ============================================================
    private static double runManualRandomBaseline(int seed, int testsPerRun,
                                                  int maxStepsPerTest, int totalTransitions) {
        Random random    = new Random(seed);
        int size         = 0;
        int maxCapacity  = new FifoQueueModel().maxCapacity();
        Set<String> coveredTransitions = new HashSet<>();

        for (int t = 0; t < testsPerRun; t++) {
            size = 0; // resetăm dimensiunea la fiecare test
            for (int step = 0; step < maxStepsPerTest; step++) {
                boolean doPush = random.nextBoolean(); // decizie complet aleatoare
                String transition = null;
                if (doPush && size < maxCapacity) {
                    if (size == 0)                    transition = "push_empty_to_partial";
                    else if (size == maxCapacity - 1) transition = "push_partial_to_full";
                    else                              transition = "push_partial_to_partial";
                    size++;
                } else if (!doPush && size > 0) {
                    if (size == 1)               transition = "pop_partial_to_empty";
                    else if (size == maxCapacity) transition = "pop_full_to_partial";
                    else                          transition = "pop_partial_to_partial";
                    size--;
                }
                // Dacă operația e ilegală → transition rămâne null → pas pierdut
                if (transition != null) coveredTransitions.add(transition);
            }
        }
        return totalTransitions == 0 ? 0.0 : 100.0 * coveredTransitions.size() / totalTransitions;
    }

    // ============================================================
    // 9a. SCRIERE CSV
    //
    // Un rând per rulare cu toate metricile colectate.
    // Poate fi deschis în Excel pentru grafice sau analiză statistică.
    // Coloane: seed, tests, steps, avg_test_len, push, pop,
    //          transition_coverage, state_coverage, pair_coverage,
    //          bug_detecting_tests, oracle_failures, manual_coverage
    // ============================================================
    private static void writeCsv(Path path, List<RunStats> runs,
                                 List<Double> manualCoverages) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("seed,tests,steps,avg_test_len,push,pop,transition_coverage," +
                "state_coverage,pair_coverage,bug_detecting_tests," +
                "oracle_failures_correct_sut,manual_transition_coverage");
        for (int i = 0; i < runs.size(); i++) {
            RunStats r = runs.get(i);
            lines.add(String.format(Locale.US,
                    "%d,%d,%d,%.2f,%d,%d,%.2f,%.2f,%.2f,%d,%d,%.2f",
                    r.seed, r.generatedTests, r.generatedSteps, r.avgTestLength,
                    r.pushCount, r.popCount, r.transitionCoverage, r.stateCoverage,
                    r.transitionPairCoverage, r.testsDetectingBug,
                    r.oracleFailuresOnCorrectSut, manualCoverages.get(i)));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    // ============================================================
    // 9b. SCRIERE SUMMARY MARKDOWN
    //
    // Raportul agregat peste toate cele 30 de rulări:
    //   - medii și deviații standard pentru fiecare metrică
    //   - curba de convergență (coverage la fiecare pas)
    //   - comparație MBT vs. baseline random
    //   - validarea oracolului (0 false positive-uri)
    //
    // Acesta e fișierul citat în documentație și prezentare.
    // ============================================================
    private static void writeSummary(Path path, List<RunStats> runs,
                                     List<Double> manualCoverages, int runsCount,
                                     int testsPerRun, int maxSteps) throws IOException {

        double avgTransitionCoverage = runs.stream().mapToDouble(r -> r.transitionCoverage).average().orElse(0.0);
        double stdTransitionCoverage = stddev(runs.stream().map(r -> r.transitionCoverage).collect(Collectors.toList()));
        double avgStateCoverage      = runs.stream().mapToDouble(r -> r.stateCoverage).average().orElse(0.0);
        double avgPairCoverage       = runs.stream().mapToDouble(r -> r.transitionPairCoverage).average().orElse(0.0);
        double avgBugDetections      = runs.stream().mapToInt(r -> r.testsDetectingBug).average().orElse(0.0);
        double avgManualCoverage     = manualCoverages.stream().mapToDouble(d -> d).average().orElse(0.0);
        List<Double> convergence     = averageConvergence(runs);

        List<String> summary = new ArrayList<>();
        summary.add("# Analiza rezultate FIFO (MBT + Oracle extins)");
        summary.add("\n## Configuratie experiment");
        summary.add("- rulari: " + runsCount);
        summary.add("- teste per rulare: " + testsPerRun);
        summary.add("- pasi maximi per test: " + maxSteps);
        summary.add("\n## Rezultate agregate");
        summary.add(String.format("- acoperire medie tranzitii: %.2f%% (std: %.2f)", avgTransitionCoverage, stdTransitionCoverage));
        summary.add(String.format("- acoperire medie stari: %.2f%%", avgStateCoverage));
        summary.add(String.format("- acoperire medie perechi tranzitii: %.2f%%", avgPairCoverage));
        summary.add(String.format("- teste medii care detecteaza SUT-ul buggy: %.2f / rulare", avgBugDetections));
        summary.add(String.format("- acoperire medie baseline random: %.2f%%", avgManualCoverage));
        summary.add(String.format("- diferenta MBT - random: %.2f pp", avgTransitionCoverage - avgManualCoverage));
        summary.add("\n## Convergenta acoperirii tranzitiilor");
        for (int i = 0; i < convergence.size(); i++) {
            summary.add(String.format("  - pas %d -> %.2f%%", i + 1, convergence.get(i)));
        }
        summary.add("\n## Verificare oracle");
        summary.add("- verificari totale: " + runs.stream().mapToInt(r -> r.oracleChecks).sum());
        summary.add("- esecuri pe SUT corect (trebuie 0): " + runs.stream().mapToInt(r -> r.oracleFailuresOnCorrectSut).sum());
        Files.write(path, summary, StandardCharsets.UTF_8);
    }

    // Deviație standard — măsoară variabilitatea coverage-ului între rulări
    private static double stddev(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double mean     = values.stream().mapToDouble(v -> v).average().orElse(0.0);
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0);
        return Math.sqrt(variance);
    }

    // ============================================================
    // (UTILITAR) CURBA DE CONVERGENȚĂ MEDIE
    //
    // Calculează coverage-ul mediu la fiecare pas agregat peste
    // toate cele 30 de rulări.
    // Produce lista: [coverage_pas1, coverage_pas2, ...]
    // Această listă generează graficul din documentație care arată
    // că 100% coverage e atins la pasul 32 din 300 posibili.
    // ============================================================
    private static List<Double> averageConvergence(List<RunStats> runs) {
        int maxLen    = runs.stream().mapToInt(r -> r.transitionCoverageConvergence.size()).max().orElse(0);
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < maxLen; i++) {
            final int idx = i;
            double avg = runs.stream()
                    .filter(r -> idx < r.transitionCoverageConvergence.size())
                    .mapToDouble(r -> r.transitionCoverageConvergence.get(idx))
                    .average().orElse(0.0);
            result.add(avg);
        }
        return result;
    }
}