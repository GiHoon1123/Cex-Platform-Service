// =====================================================
// BalanceCache - 메모리 기반 잔고 관리
// =====================================================
// 역할: 사용자 잔고를 메모리에 캐싱하여 초고속 조회/업데이트
// 
// 핵심 설계:
// 1. HashMap으로 O(1) 조회/업데이트
// 2. available + locked 분리 관리
// 3. 메모리만 사용 (DB 제외, 벤치마크용)
//
// 잔고 상태 변화:
// 1. 주문 생성 → available 차감, locked 증가
// 2. 주문 체결 → locked 차감, 상대방 available 증가
// 3. 주문 취소 → locked 차감, available 증가
//
// 자료구조:
// 1. HashMap<BalanceKey, Balance>
//    - BalanceKey: (userId, mint) 튜플 → 커스텀 Key 클래스
//    - Balance: (available, locked) 튜플
//    * 조회: O(1) average
//    * 업데이트: O(1) average
//    * 장점: 매우 빠른 잔고 조회
//    * 단점/한계:
//      - Key 객체 생성 비용 (BalanceKey 클래스)
//      - Rust: 튜플이 인라인 (더 효율적)
//      - 메모리 오버헤드 (객체 헤더 16 bytes)
//      - 해시 충돌 시 O(n) (드물지만 발생 가능)
//
// 작동 방식:
// 1. 잔고 조회: BalanceKey로 HashMap에서 조회 (없으면 0으로 초기화)
// 2. 잔고 잠금: available 차감, locked 증가
// 3. 잔고 이체: 한 사용자에서 다른 사용자로 잔고 이동
// 4. 잔고 업데이트: available 증가/감소 (입금/출금)
// =====================================================

package dustin.cex.domains.cex.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Objects;

/**
 * 잔고 키
 * (userId, mint) 튜플
 */
class BalanceKey {
    private final long userId;
    private final String mint;
    
    public BalanceKey(long userId, String mint) {
        this.userId = userId;
        this.mint = mint;
    }
    
    public long getUserId() {
        return userId;
    }
    
    public String getMint() {
        return mint;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BalanceKey that = (BalanceKey) o;
        return userId == that.userId && Objects.equals(mint, that.mint);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(userId, mint);
    }
}

/**
 * 사용자별 자산별 잔고
 * 
 * 예시:
 * (123, "SOL") -> Balance { available: 10.0, locked: 1.0 }
 * (123, "USDT") -> Balance { available: 1000.0, locked: 50.0 }
 */
class Balance {
    /**
     * 사용 가능 잔고 (즉시 거래 가능)
     */
    private BigDecimal available;
    
    /**
     * 잠긴 잔고 (주문에 사용 중)
     */
    private BigDecimal locked;
    
    /**
     * 새 잔고 생성 (모두 0)
     */
    public Balance() {
        this.available = BigDecimal.ZERO;
        this.locked = BigDecimal.ZERO;
    }
    
    /**
     * 초기 잔고로 생성
     * 
     * @param available 사용 가능 잔고
     * @param locked 잠긴 잔고
     */
    public Balance(BigDecimal available, BigDecimal locked) {
        // setter를 사용하여 정규화 적용
        setAvailable(available);
        setLocked(locked);
    }
    
    public BigDecimal getAvailable() {
        return available;
    }
    
    public void setAvailable(BigDecimal available) {
        // 저장 시 정규화하여 정밀도 문제 방지
        this.available = BalanceCache.normalize(available);
    }
    
    public BigDecimal getLocked() {
        return locked;
    }
    
    public void setLocked(BigDecimal locked) {
        // 저장 시 정규화하여 정밀도 문제 방지
        this.locked = BalanceCache.normalize(locked);
    }
    
    /**
     * 총 잔고 (available + locked)
     * 
     * @return 총 잔고
     */
    public BigDecimal getTotal() {
        return available.add(locked);
    }
}

/**
 * 메모리 기반 잔고 캐시
 * 
 * 구조:
 * HashMap {
 *   (user_id, mint) -> Balance { available, locked }
 * }
 * 
 * 예시:
 * (123, "SOL") -> { available: 10.0, locked: 1.0 }
 * (123, "USDT") -> { available: 1000.0, locked: 50.0 }
 * (456, "SOL") -> { available: 5.0, locked: 0.0 }
 */
public class BalanceCache {
    /**
     * Key: (userId, mint)
     * Value: Balance
     */
    private final HashMap<BalanceKey, Balance> balances;
    
    /**
     * 새 BalanceCache 생성
     */
    public BalanceCache() {
        this.balances = new HashMap<>();
    }
    
    /**
     * 용량 지정하여 생성 (메모리 사전 할당)
     * 
     * with_capacity()는 HashMap 내부 배열 크기를 미리 확보
     * 재할당(rehash) 방지로 성능 향상
     * 
     * @param capacity 초기 용량
     */
    public BalanceCache(int capacity) {
        this.balances = new HashMap<>(capacity);
    }
    
    /**
     * 잔고 조회 (없으면 0으로 초기화)
     * 
     * @param userId 사용자 ID
     * @param mint 자산 주소
     * @return 잔고 (가변 참조)
     */
    public Balance getBalanceMut(long userId, String mint) {
        BalanceKey key = new BalanceKey(userId, mint);
        // entry가 없으면 새로 생성하고 참조 반환
        return balances.computeIfAbsent(key, k -> new Balance());
    }
    
    /**
     * 잔고 조회 (읽기 전용, 없으면 null)
     * 
     * @param userId 사용자 ID
     * @param mint 자산 주소
     * @return 잔고, 없으면 null
     */
    public Balance getBalance(long userId, String mint) {
        BalanceKey key = new BalanceKey(userId, mint);
        return balances.get(key);
    }
    
    /**
     * 사용 가능 잔고 확인
     * 
     * @param userId 사용자 ID
     * @param mint 자산 주소
     * @param requiredAmount 필요한 금액
     * @return 잔고가 충분하면 true
     */
    public boolean checkSufficientBalance(long userId, String mint, BigDecimal requiredAmount) {
        Balance balance = getBalance(userId, mint);
        if (balance == null) {
            return false;
        }
        return balance.getAvailable().compareTo(requiredAmount) >= 0;
    }
    
    /**
     * 잔고 잠금 (주문 생성 시)
     * available 차감 → locked 증가
     * 
     * @param userId 사용자 ID
     * @param mint 자산 주소
     * @param amount 잠글 금액
     * @throws IllegalArgumentException 잔고 부족 시
     */
    public void lockBalance(long userId, String mint, BigDecimal amount) {
        // 정규화 (BigDecimal 정밀도 문제 해결)
        BigDecimal normalizedAmount = normalize(amount);
        Balance balance = getBalanceMut(userId, mint);
        
        // 잔고 확인
        BigDecimal available = normalize(balance.getAvailable());
        if (!isGreaterOrEqual(available, normalizedAmount)) {
            throw new IllegalArgumentException(
                String.format("Insufficient balance: user=%d, mint=%s, required=%s, available=%s",
                    userId, mint, normalizedAmount, available)
            );
        }
        
        // 잠금 실행
        balance.setAvailable(normalize(available.subtract(normalizedAmount)));
        balance.setLocked(normalize(normalize(balance.getLocked()).add(normalizedAmount)));
    }
    
    /**
     * 잔고 잠금 해제 (주문 취소 시)
     * locked 차감 → available 증가
     * 
     * @param userId 사용자 ID
     * @param mint 자산 주소
     * @param amount 해제할 금액
     * @throws IllegalArgumentException 잠긴 잔고 부족 시
     */
    public void unlockBalance(long userId, String mint, BigDecimal amount) {
        // 정규화 (BigDecimal 정밀도 문제 해결)
        BigDecimal normalizedAmount = normalize(amount);
        Balance balance = getBalanceMut(userId, mint);
        
        BigDecimal locked = normalize(balance.getLocked());
        if (!isGreaterOrEqual(locked, normalizedAmount)) {
            throw new IllegalArgumentException("Not enough locked balance to unlock");
        }
        
        balance.setLocked(normalize(locked.subtract(normalizedAmount)));
        balance.setAvailable(normalize(normalize(balance.getAvailable()).add(normalizedAmount)));
    }
    
    /**
     * BigDecimal 정규화 (소수점 이하 18자리로 제한, 금융 계산용)
     * 
     * BigDecimal 연산 시 스케일이 누적될 수 있어 정규화가 필요합니다.
     * 암호화폐 계산에서는 소수점 이하 18자리를 일반적으로 사용합니다.
     */
    static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        // 소수점 이하 18자리로 제한, HALF_UP 반올림
        return value.setScale(18, RoundingMode.HALF_UP);
    }
    
    /**
     * BigDecimal 비교 (작은 오차 허용)
     * 
     * BigDecimal 연산 과정에서 발생하는 미세한 오차를 허용합니다.
     * 두 값의 차이가 1e-18 미만이면 같다고 판단합니다.
     */
    private static boolean isGreaterOrEqual(BigDecimal a, BigDecimal b) {
        BigDecimal diff = normalize(a).subtract(normalize(b));
        // 차이가 -1e-18 이상이면 a >= b로 판단
        return diff.compareTo(new BigDecimal("-0.000000000000000001")) >= 0;
    }
    
    /**
     * 잔고 이체 (체결 시)
     * from_user의 locked 차감 → to_user의 available 증가
     * 
     * @param fromUser 보내는 사용자
     * @param toUser 받는 사용자
     * @param mint 자산 주소
     * @param amount 이체 금액
     * @param fromLocked from_user가 locked에서 차감? (true: locked, false: available)
     * @throws IllegalArgumentException 잔고 부족 시
     */
    public void transfer(long fromUser, long toUser, String mint, BigDecimal amount, boolean fromLocked) {
        // 정규화 (BigDecimal 정밀도 문제 해결)
        BigDecimal normalizedAmount = normalize(amount);
        
        // from_user 차감
        Balance fromBalance = getBalanceMut(fromUser, mint);
        if (fromLocked) {
            BigDecimal locked = normalize(fromBalance.getLocked());
            if (!isGreaterOrEqual(locked, normalizedAmount)) {
                throw new IllegalArgumentException(
                    String.format("Insufficient locked balance: user=%d, mint=%s, required=%s, locked=%s, available=%s",
                        fromUser, mint, normalizedAmount, locked, fromBalance.getAvailable())
                );
            }
            fromBalance.setLocked(normalize(locked.subtract(normalizedAmount)));
        } else {
            BigDecimal available = normalize(fromBalance.getAvailable());
            if (!isGreaterOrEqual(available, normalizedAmount)) {
                throw new IllegalArgumentException(
                    String.format("Insufficient available balance: user=%d, mint=%s, required=%s, available=%s, locked=%s",
                        fromUser, mint, normalizedAmount, available, fromBalance.getLocked())
                );
            }
            fromBalance.setAvailable(normalize(available.subtract(normalizedAmount)));
        }
        
        // to_user 증가
        Balance toBalance = getBalanceMut(toUser, mint);
        toBalance.setAvailable(normalize(toBalance.getAvailable().add(normalizedAmount)));
    }
    
    /**
     * 초기 잔고 설정 (테스트용)
     * 
     * @param userId 사용자 ID
     * @param mint 자산 주소
     * @param available 사용 가능 잔고
     * @param locked 잠긴 잔고
     */
    public void setBalance(long userId, String mint, BigDecimal available, BigDecimal locked) {
        BalanceKey key = new BalanceKey(userId, mint);
        Balance balance = new Balance();
        balance.setAvailable(available);  // setter에서 정규화됨
        balance.setLocked(locked);  // setter에서 정규화됨
        balances.put(key, balance);
    }
    
    /**
     * 사용 가능 잔고 증가/감소 (입금/출금용)
     * 
     * 처리 과정:
     * 1. 잔고 조회 (없으면 0으로 초기화)
     * 2. available += delta
     * 3. 음수 잔고 방지 (출금 시 잔고 부족 체크는 호출자가 해야 함)
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param delta 증감량 (양수: 입금, 음수: 출금)
     * 
     * 예시:
     * <pre>
     * // 100 USDT 입금
     * cache.addAvailable(123, "USDT", new BigDecimal("100"));
     * 
     * // 50 USDT 출금
     * cache.addAvailable(123, "USDT", new BigDecimal("-50"));
     * </pre>
     */
    public void addAvailable(long userId, String mint, BigDecimal delta) {
        Balance balance = getBalanceMut(userId, mint);
        balance.setAvailable(balance.getAvailable().add(delta));
        
        // 음수 잔고 방지 (안전장치)
        if (balance.getAvailable().compareTo(BigDecimal.ZERO) < 0) {
            balance.setAvailable(BigDecimal.ZERO);
        }
    }
    
    /**
     * 모든 잔고 삭제 (벤치마크/테스트 초기화용)
     */
    public void clear() {
        balances.clear();
    }
}

