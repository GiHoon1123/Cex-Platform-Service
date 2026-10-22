package dustin.cex.domains.auth.model.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Refresh Token 엔티티
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
    @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
    @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash"),
    @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {
    
    // 접근 토큰을 영속 세션과 연결한다. 토큰 회수 후에는 접근 토큰도 사용할 수 없다.
    @Column(nullable = false, unique = true, updatable = false)
    @Builder.Default
    private java.util.UUID sessionId = java.util.UUID.randomUUID();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;
    
    @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "TEXT")
    @lombok.ToString.Exclude
    private String tokenHash;
    
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    
    // 운영 세션은 보안키 검증 시각을 서버에 보관한다. JWT의 주장만으로 권한을 높이지 않는다.
    private LocalDateTime mfaVerifiedAt;

    // 세션을 인증한 공개키 지문이다. 분실 키를 폐기할 때 다른 키로 인증했는지 확인한다.
    @Column(length=64)
    private String mfaCredentialFingerprint;

    @Column(nullable = false)
    @Builder.Default
    private Boolean revoked = false;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    // 시각만 남은 과거 세션을 검증된 세션으로 취급하지 않는다. 실제 서명 키 결합도 필수다.
    @jakarta.persistence.Transient
    public boolean hasVerifiedMfa() {
        return mfaVerifiedAt!=null && mfaCredentialFingerprint!=null && mfaCredentialFingerprint.matches("[0-9a-f]{64}");
    }

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
