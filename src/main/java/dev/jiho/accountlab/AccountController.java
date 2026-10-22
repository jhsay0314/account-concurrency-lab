package dev.jiho.accountlab;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {
    private final AccountService service;
    public AccountController(AccountService service) { this.service = service; }
    public record Issue(@NotBlank @Pattern(regexp = "[a-z0-9]{1,20}") String prefix) {}
    public record Claim(@NotBlank @Size(max = 64) String requestId,
                        @NotNull AccountService.Strategy strategy) {}
    public record Result(long accountId) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result issue(@Valid @RequestBody Issue input) { return new Result(service.issue(input.prefix())); }

    @PostMapping("/{id}/claim")
    public Result claim(@PathVariable long id, @Valid @RequestBody Claim input) {
        return new Result(service.claim(id, input.requestId(), input.strategy()));
    }

    @ExceptionHandler(AccountService.MissingAccountException.class)
    ResponseEntity<Void> missing() { return ResponseEntity.notFound().build(); }

    @ExceptionHandler({Account.AlreadyClaimedException.class, OptimisticLockingFailureException.class,
        PessimisticLockingFailureException.class, AccountService.AttemptsExhaustedException.class})
    ResponseEntity<ProblemDetail> conflict() {
        return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "Concurrent claim or allocation conflict"));
    }
}
