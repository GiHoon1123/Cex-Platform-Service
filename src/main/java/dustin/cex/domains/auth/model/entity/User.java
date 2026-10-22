package dustin.cex.domains.auth.model.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 사용자 엔티티
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_email", columnList = "email")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    
    // 공개 가입에는 역할 입력을 받지 않는다. 운영 권한은 별도 초기화 또는 독립 승인된 역할 부여로만 부여한다.
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserRole role = UserRole.CUSTOMER;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean tradingRestricted = false;

    // 같은 사람이 계정을 두 개 만들어 자기 승인을 하는 경우까지 차단할 식별자다.
    // 신원 확인과 식별자 발급의 신뢰성은 조직의 인사·권한 부여 절차가 책임진다.
    private java.util.UUID operatorPersonId;

    // 운영 권한은 영구 부여하지 않는다. 복구 요청이 있으면 승인·재등록 전까지 인증을 막는다.
    // 고객에게는 이 필드를 요구하지 않으며, 만료 없는 운영 계정은 허용하지 않는다.
    private java.time.Instant operatorAccessExpiresAt;
    private java.time.Instant operatorLastReviewedAt;
    private java.util.UUID operatorRecoveryRequestId;

    @jakarta.persistence.Transient
    public boolean isAuthenticationAllowed() {
        return enabled && (role==UserRole.CUSTOMER || (operatorPersonId!=null
            && operatorAccessExpiresAt!=null && operatorAccessExpiresAt.isAfter(java.time.Instant.now())
            && operatorRecoveryRequestId==null));
    }

    @jakarta.persistence.Version
    private long version;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true, length = 255)
    private String email;
    
    @Column(name = "password_hash", nullable = false, length = 255)
    @lombok.ToString.Exclude
    private String passwordHash;
    
    @Column(length = 100)
    private String username;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
