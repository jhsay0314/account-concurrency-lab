package dev.jiho.accountlab;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(ConcurrencyTest.Database.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class ConcurrencyTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            // Boot keeps the database alive until dependent application beans close.
            return new MySQLContainer<>("mysql:8.4");
        }
    }
    @Autowired AccountRepository accounts;
    @Autowired AccountService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach void clear() { accounts.deleteAll(); }

    @Test void unguardedReadThenWriteAcknowledgesTwoOwners() throws Exception {
        long id = accounts.saveAndFlush(new Account("baseline")).getId();
        var readTogether = new CyclicBarrier(2);
        var tx = new TransactionTemplate(manager);
        List<Boolean> results = race(2, worker -> tx.execute(status -> {
            boolean available = jdbc.queryForObject(
                "select available from accounts where id=?", Boolean.class, id);
            await(readTogether);
            if (available) {
                // Deliberately unsafe baseline, only in tests; bypasses JPA @Version.
                jdbc.update("update accounts set available=false, claimed_by=? where id=?",
                    "worker-" + worker, id);
            }
            return available;
        }));
        assertThat(results).containsExactly(true, true);
        assertThat(accounts.findById(id).orElseThrow().getClaimedBy()).startsWith("worker-");
    }

    @Test void optimisticVersionAllowsOnlyOneCommitAfterOverlappingReads() throws Exception {
        long id = accounts.saveAndFlush(new Account("optimistic")).getId();
        var readTogether = new CyclicBarrier(2);
        List<String> results = race(2, worker -> {
            try {
                service.claim(id, "worker-" + worker, AccountService.Strategy.OPTIMISTIC,
                    () -> await(readTogether));
                return "worker-" + worker;
            } catch (OptimisticLockingFailureException expected) { return "conflict"; }
        });
        assertThat(results.stream().filter("conflict"::equals).count()).isEqualTo(1);
        String winner = results.stream().filter(s -> !s.equals("conflict")).findFirst().orElseThrow();
        assertThat(accounts.findById(id).orElseThrow().getClaimedBy()).isEqualTo(winner);
    }

    @Test void pessimisticClaimSerializesEightRequests() throws Exception {
        long id = accounts.saveAndFlush(new Account("pessimistic")).getId();
        List<String> results = race(8, worker -> {
            try {
                service.claim(id, "worker-" + worker, AccountService.Strategy.PESSIMISTIC);
                return "worker-" + worker;
            } catch (Account.AlreadyClaimedException expected) { return "conflict"; }
        });
        assertThat(results.stream().filter("conflict"::equals).count()).isEqualTo(7);
        String winner = results.stream().filter(s -> !s.equals("conflict")).findFirst().orElseThrow();
        Account stored = accounts.findById(id).orElseThrow();
        assertThat(stored.getClaimedBy()).isEqualTo(winner);
        assertThat(stored.isAvailable()).isFalse();
    }

    @Test void simultaneousNewIdsRetryInFreshTransactions() throws Exception {
        var firstCandidateTogether = new CyclicBarrier(8);
        List<Long> ids = race(8, worker -> {
            AtomicInteger attempts = new AtomicInteger();
            return service.issue(() -> {
                if (attempts.getAndIncrement() == 0) {
                    await(firstCandidateTogether);
                    return "same-id";
                }
                return "unique-" + worker;
            }, 2);
        });
        assertThat(ids).hasSize(8).doesNotHaveDuplicates();
        assertThat(accounts.count()).isEqualTo(8);
        assertThat(accounts.findAll().stream().map(Account::getLoginId)).doesNotHaveDuplicates();
    }

    @Test void exhaustedCollisionRetriesDoNotCreateExtraRows() {
        accounts.saveAndFlush(new Account("taken"));
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> service.issue(() -> {
            attempts.incrementAndGet();
            return "taken";
        }, 3)).isInstanceOf(AccountService.AttemptsExhaustedException.class);
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(accounts.count()).isEqualTo(1);
    }

    @Test void catchingConstraintFailureInsideSameTransactionDoesNotRecoverIt() {
        accounts.saveAndFlush(new Account("taken"));
        var tx = new TransactionTemplate(manager);
        // Repository marks the joined transaction rollback-only. Catching does not clear it.
        assertThatThrownBy(() -> tx.execute(status -> {
            try {
                accounts.saveAndFlush(new Account("taken"));
            } catch (DataIntegrityViolationException expected) {
                assertThat(status.isRollbackOnly()).isTrue();
            }
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(service.issue(() -> "fresh-transaction", 1)).isPositive();
    }

    @Test void unrelatedIntegrityFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> service.issue(() -> {
            attempts.incrementAndGet();
            return null;
        }, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test void missingAccountIsDistinctFromConflict() {
        assertThatThrownBy(() -> service.claim(Long.MAX_VALUE, "worker",
            AccountService.Strategy.PESSIMISTIC)).isInstanceOf(AccountService.MissingAccountException.class);
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(15, TimeUnit.SECONDS); }
        catch (Exception ex) { throw new IllegalStateException("Worker synchronization failed", ex); }
    }

    private static <T> List<T> race(int workers, IntFunction<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        var start = new CyclicBarrier(workers);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int worker = i;
                futures.add(executor.submit(() -> { await(start); return action.apply(worker); }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(40, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not terminate");
            }
        }
    }
}
