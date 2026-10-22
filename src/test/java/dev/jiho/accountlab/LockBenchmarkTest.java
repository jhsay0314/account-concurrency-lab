package dev.jiho.accountlab;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;

/** Closed-loop, synchronized bursts. Measures service calls including transaction commit, not HTTP. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(ConcurrencyTest.Database.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "lab.benchmark", matches = "true")
class LockBenchmarkTest {
    @Autowired AccountService service;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;

    record Sample(long account, String owner, String outcome, long nanos) {}

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void compareStrategies() throws Exception {
        Path directory = Path.of("target", "benchmarks", "run-" + System.currentTimeMillis());
        Files.createDirectories(directory);
        StringBuilder raw = new StringBuilder("scenario,workers,round,strategy,wave,account,owner,outcome,service_ms\n");
        StringBuilder summary = new StringBuilder("scenario,workers,round,strategy,requests,success,already_claimed,optimistic_conflict,lock_failure,wall_ms,completed_per_sec,success_per_sec,p50_ms,p95_ms,p99_ms,success_p95_ms,rejected_p95_ms\n");
        Files.writeString(directory.resolve("environment.txt"),
            "status=RUNNING\nstarted=" + Instant.now() + "\njava=" + System.getProperty("java.version")
            + "\nos=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
            + "\nprocessors=" + Runtime.getRuntime().availableProcessors()
            + "\nmax_heap_bytes=" + Runtime.getRuntime().maxMemory()
            + "\nmysql=" + jdbc.queryForObject("select version()", String.class)
            + "\nisolation=" + jdbc.queryForObject("select @@transaction_isolation", String.class)
            + "\npool_max=16\nrounds=5\nwaves_per_round=10\nwarmup_rounds_per_strategy=1\nclaim_retries=0\n");
        for (String scenario : List.of("independent", "contended")) {
            for (int workers : List.of(8, 16)) {
                ExecutorService executor = Executors.newFixedThreadPool(workers);
                try {
                    for (int round = 0; round <= 5; round++) {
                        // Reverse order each round to reduce systematic warm-up/order bias.
                        List<AccountService.Strategy> order = new ArrayList<>(List.of(AccountService.Strategy.values()));
                        if (round % 2 == 0) Collections.reverse(order);
                        for (var strategy : order) {
                            List<Sample> samples = new ArrayList<>();
                            long wallNanos = 0;
                            for (int wave = 0; wave < 10; wave++) {
                                accounts.deleteAllInBatch();
                                List<Account> seeds = new ArrayList<>();
                                int accountCount = scenario.equals("contended") ? 1 : workers;
                                for (int i = 0; i < accountCount; i++) seeds.add(new Account("seed-" + i));
                                List<Account> saved = accounts.saveAllAndFlush(seeds);
                                var ready = new CountDownLatch(workers);
                                var start = new CountDownLatch(1);
                                List<Future<Sample>> futures = new ArrayList<>();
                                for (int i = 0; i < workers; i++) {
                                    long id = saved.get(i % accountCount).getId();
                                    String owner = "worker-" + i;
                                    futures.add(executor.submit(() -> {
                                        ready.countDown();
                                        if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                                        long before = System.nanoTime();
                                        String outcome;
                                        try {
                                            service.claim(id, owner, strategy);
                                            outcome = "success";
                                        } catch (Account.AlreadyClaimedException ex) { outcome = "already_claimed"; }
                                        catch (OptimisticLockingFailureException ex) { outcome = "optimistic_conflict"; }
                                        catch (PessimisticLockingFailureException ex) { outcome = "lock_failure"; }
                                        return new Sample(id, owner, outcome, System.nanoTime() - before);
                                    }));
                                }
                                if (!ready.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Ready timeout");
                                long beforeWave = System.nanoTime();
                                start.countDown();
                                List<Sample> waveSamples = new ArrayList<>();
                                for (Future<Sample> f : futures) waveSamples.add(f.get(30, TimeUnit.SECONDS));
                                wallNanos += System.nanoTime() - beforeWave;
                                // Verify persisted ownership, outside the measurement interval.
                                assertThat(waveSamples.stream().filter(s -> s.outcome().equals("success")).count())
                                    .isEqualTo(accountCount);
                                assertThat(waveSamples.stream().filter(s -> s.outcome().equals("lock_failure")).count()).isZero();
                                for (Account account : accounts.findAll()) {
                                    assertThat(account.isAvailable()).isFalse();
                                    assertThat(waveSamples.stream().filter(s -> s.account() == account.getId()
                                        && s.outcome().equals("success") && s.owner().equals(account.getClaimedBy())).count())
                                        .isEqualTo(1);
                                }
                                samples.addAll(waveSamples);
                                if (round > 0) for (Sample s : waveSamples) raw.append(String.format(Locale.ROOT,
                                    "%s,%d,%d,%s,%d,%d,%s,%s,%.6f%n", scenario, workers, round, strategy,
                                    wave, s.account(), s.owner(), s.outcome(), s.nanos() / 1e6));
                            }
                            if (round > 0) {
                                long success = count(samples, "success");
                                summary.append(String.format(Locale.ROOT,
                                    "%s,%d,%d,%s,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%s,%s%n",
                                    scenario, workers, round, strategy, samples.size(), success,
                                    count(samples, "already_claimed"), count(samples, "optimistic_conflict"),
                                    count(samples, "lock_failure"), wallNanos / 1e6, samples.size() * 1e9 / wallNanos,
                                    success * 1e9 / wallNanos, percentile(samples, .50), percentile(samples, .95),
                                    percentile(samples, .99), percentileText(samples, true), percentileText(samples, false)));
                                Files.writeString(directory.resolve("samples.csv"), raw);
                                Files.writeString(directory.resolve("summary.csv"), summary);
                                System.out.printf("Benchmark %s workers=%d round=%d %s complete%n", scenario, workers, round, strategy);
                            }
                        }
                    }
                } finally {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker shutdown timeout");
                }
            }
        }
        Files.writeString(directory.resolve("environment.txt"), "status=COMPLETED\nfinished=" + Instant.now() + "\n",
            StandardOpenOption.APPEND);
        System.out.println("Benchmark results: " + directory.toAbsolutePath());
    }

    static long count(List<Sample> samples, String outcome) {
        return samples.stream().filter(s -> s.outcome().equals(outcome)).count();
    }
    static double percentile(List<Sample> samples, double p) {
        long[] sorted = samples.stream().mapToLong(Sample::nanos).sorted().toArray();
        return sorted[(int) Math.ceil(sorted.length * p) - 1] / 1e6;
    }
    static String percentileText(List<Sample> samples, boolean success) {
        List<Sample> subset = samples.stream().filter(s -> s.outcome().equals("success") == success).toList();
        return subset.isEmpty() ? "" : String.format(Locale.ROOT, "%.3f", percentile(subset, .95));
    }
}
