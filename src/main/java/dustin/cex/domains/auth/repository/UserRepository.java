package dustin.cex.domains.auth.repository;

import dustin.cex.domains.auth.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 사용자 Repository
 * User Repository
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * 이메일로 사용자 조회
     * Find user by email
     */
    Optional<User> findByEmail(String email);
    
    /**
     * 이메일 존재 여부 확인
     * Check if email exists
     */
    boolean existsByEmail(String email);

    // 사용자 행을 먼저 잠가 로그인·갱신·권한 변경의 잠금 순서를 통일한다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
    Optional<User> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.email = :email")
    Optional<User> lockByEmail(@org.springframework.data.repository.query.Param("email") String email);
}
