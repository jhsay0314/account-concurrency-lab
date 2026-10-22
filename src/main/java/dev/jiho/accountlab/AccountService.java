package dev.jiho.accountlab;

import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService {
    public enum Strategy { OPTIMISTIC, PESSIMISTIC }
    private final AccountRepository accounts;
    private final TransactionTemplate attempt;

    public AccountService(AccountRepository accounts, PlatformTransactionManager manager) {
        this.accounts = accounts;
        this.attempt = new TransactionTemplate(manager);
        attempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        attempt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        attempt.setTimeout(10);
    }

    public long claim(long id, String requestId, Strategy strategy) {
        return claim(id, requestId, strategy, () -> {});
    }

    // Package-private synchronization seam: tests force overlapping reads without sleeps.
    long claim(long id, String requestId, Strategy strategy, Runnable afterRead) {
        return attempt.execute(status -> {
            Account account = (strategy == Strategy.PESSIMISTIC
                ? accounts.findLocked(id) : accounts.findById(id)).orElseThrow(MissingAccountException::new);
            afterRead.run();
            account.claim(requestId);
            accounts.flush();
            return account.getId();
        });
    }

    public long issue(String prefix) {
        return issue(() -> prefix + "-" + java.util.UUID.randomUUID(), 3);
    }

    long issue(Supplier<String> candidate, int maxAttempts) {
        for (int n = 0; n < maxAttempts; n++) {
            String loginId = candidate.get();
            try {
                return attempt.execute(status -> accounts.saveAndFlush(new Account(loginId)).getId());
            } catch (DataIntegrityViolationException ex) {
                // Only the named login uniqueness constraint is retryable.
                if (!isLoginCollision(ex)) throw ex;
            }
        }
        throw new AttemptsExhaustedException();
    }

    private boolean isLoginCollision(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return name != null && (name.equals("uk_login_id") || name.endsWith(".uk_login_id"));
            }
        }
        return false;
    }

    public static class MissingAccountException extends RuntimeException {}
    public static class AttemptsExhaustedException extends RuntimeException {}
}
