package dev.jiho.accountlab;

import jakarta.persistence.*;

@Entity
@Table(name = "accounts", uniqueConstraints = @UniqueConstraint(name = "uk_login_id", columnNames = "login_id"))
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "login_id", nullable = false, length = 64)
    private String loginId;
    @Column(nullable = false)
    private boolean available;
    @Column(name = "claimed_by", length = 64)
    private String claimedBy;
    @Version
    private long version;

    protected Account() {}
    public Account(String loginId) {
        this.loginId = loginId;
        this.available = true;
    }
    public void claim(String requestId) {
        if (!available) throw new AlreadyClaimedException();
        available = false;
        claimedBy = requestId;
    }
    public Long getId() { return id; }
    public String getLoginId() { return loginId; }
    public boolean isAvailable() { return available; }
    public String getClaimedBy() { return claimedBy; }

    public static class AlreadyClaimedException extends RuntimeException {}
}
