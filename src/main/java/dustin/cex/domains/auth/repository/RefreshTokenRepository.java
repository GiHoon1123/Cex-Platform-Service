package dustin.cex.domains.auth.repository;

import dustin.cex.domains.auth.model.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 회수 가능한 영속 세션과 갱신 토큰 해시를 조회한다.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    
    /**
     * 토큰 해시로 Refresh Token 조회
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    @Query("select t.userId from RefreshToken t where t.tokenHash = :hash")
    Optional<Long> findOwnerByHash(@Param("hash") String hash);
    Optional<RefreshToken> findBySessionId(java.util.UUID sessionId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> lockByHash(@Param("hash") String hash);
    
    /**
     * 사용자의 모든 Refresh Token 무효화
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.updatedAt = :now WHERE rt.userId = :userId AND rt.revoked = false")
    void revokeAllByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
    
    // 비밀번호 단계의 고객 재로그인은 기존 서명 완료 세션을 끊지 않고 이전 대기 세션만 교체한다.
    // 호출자는 사용자 행을 잠가 발급·등록·인증과의 순서를 직렬화해야 한다.
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked=true,rt.updatedAt=:now WHERE rt.userId=:userId AND rt.revoked=false AND (rt.mfaVerifiedAt IS NULL OR rt.mfaCredentialFingerprint IS NULL)")
    void revokePendingByUserId(@Param("userId") Long userId,@Param("now") LocalDateTime now);

    /**
     * 사용자의 유효한 Refresh Token 개수 조회
     */
    @Query("SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.userId = :userId AND rt.revoked = false AND rt.expiresAt > :now")
    long countValidByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
