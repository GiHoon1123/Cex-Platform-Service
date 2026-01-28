package dustin.cex.domains.order.model.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 주문 엔티티
 * Order Entity
 * 
 * 역할:
 * - 거래소에서 사용자가 생성한 매수/매도 주문을 데이터베이스에 저장
 * - 주문의 생성, 상태 변경, 체결 정보를 관리
 * 
 * 주문 타입:
 * - orderType: 'buy' (매수) 또는 'sell' (매도)
 * - orderSide: 'limit' (지정가) 또는 'market' (시장가)
 * 
 * 주문 상태:
 * - 'pending': 대기 중 (아직 체결 안 됨)
 * - 'partial': 부분 체결 (일부만 체결됨)
 * - 'filled': 전량 체결 완료
 * - 'cancelled': 주문 취소됨
 * 
 * 예시:
 * - 지정가 매수: "SOL을 100 USDT에 1개 구매하고 싶다"
 *   → orderType='buy', orderSide='limit', price=100.0, amount=1.0
 * - 시장가 매도: "지금 시장가로 SOL 1개 판매하고 싶다"
 *   → orderType='sell', orderSide='market', price=null, amount=1.0
 * 
 * 데이터베이스 매핑:
 * - 테이블명: orders
 * - ID: BIGSERIAL (자동 증가)
 * - 외래키: user_id → users(id) ON DELETE CASCADE
 */
@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    
    /**
     * 주문 고유 ID
     * Order ID
     * 
     * 데이터베이스에서 자동 생성되는 BIGSERIAL 값
     * JavaScript 정밀도 손실 방지를 위해 API 응답 시 문자열로 변환 필요
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    
    /**
     * 주문한 사용자 ID
     * User ID who created this order
     * 
     * users 테이블의 외래키
     * 사용자 삭제 시 CASCADE로 주문도 함께 삭제됨
     * 봇 주문의 경우 특별한 user_id 사용 가능
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    /**
     * 주문 유형
     * Order Type
     * 
     * - 'buy': 매수 주문 (USDT로 다른 자산 구매)
     * - 'sell': 매도 주문 (보유 자산을 USDT로 판매)
     * 
     * 데이터베이스 제약조건: CHECK (order_type IN ('buy', 'sell'))
     */
    @Column(name = "order_type", nullable = false, length = 20)
    private String orderType;
    
    /**
     * 주문 방식
     * Order Side
     * 
     * - 'limit': 지정가 주문 (원하는 가격에 주문 등록, 매칭될 때까지 대기)
     * - 'market': 시장가 주문 (즉시 체결, 오더북의 최적 가격으로 매칭)
     * 
     * 데이터베이스 제약조건: CHECK (order_side IN ('limit', 'market'))
     */
    @Column(name = "order_side", nullable = false, length = 20)
    private String orderSide;
    
    /**
     * 기준 자산
     * Base Asset
     * 
     * 구매/판매하려는 자산 (예: SOL, USDC, RAY 등)
     * 예: SOL/USDT 거래 → baseMint='SOL'
     */
    @Column(name = "base_mint", nullable = false, length = 255)
    private String baseMint;
    
    /**
     * 기준 통화
     * Quote Currency
     * 
     * 항상 'USDT'가 기준 통화
     * 예: SOL/USDT 거래 → quoteMint='USDT'
     */
    @Column(name = "quote_mint", nullable = false, length = 255)
    @Builder.Default
    private String quoteMint = "USDT";
    
    /**
     * 지정가 가격
     * Limit Price
     * 
     * 지정가 주문만 필요, 시장가 주문은 NULL
     * USDT 기준 가격 (1 SOL = 100 USDT 라면 price=100.0)
     * 
     * 데이터베이스: DECIMAL(30, 9) - 소수점 9자리까지 지원
     */
    @Column(name = "price", precision = 30, scale = 9)
    private BigDecimal price;
    
    /**
     * 주문 수량
     * Order Amount
     * 
     * 주문한 총 수량 (baseMint 기준)
     * 예: SOL 10개 주문 → amount=10.0
     * 
     * 데이터베이스: DECIMAL(30, 9) - 소수점 9자리까지 지원
     */
    @Column(name = "amount", nullable = false, precision = 30, scale = 9)
    private BigDecimal amount;
    
    /**
     * 체결된 수량
     * Filled Amount
     * 
     * 부분 체결 가능
     * 예: SOL 10개 주문 → 3개 체결 → amount=10.0, filledAmount=3.0
     * filledAmount == amount면 주문이 전량 체결된 것
     * 
     * 데이터베이스: DECIMAL(30, 9), 기본값: 0
     */
    @Column(name = "filled_amount", nullable = false, precision = 30, scale = 9)
    @Builder.Default
    private BigDecimal filledAmount = BigDecimal.ZERO;
    
    /**
     * 체결된 금액 (USDT 기준)
     * Filled Quote Amount
     * 
     * 체결된 거래의 총 결제 금액 (USDT 기준)
     * 시장가 주문의 경우, 모든 체결의 (가격 * 수량) 합계
     * 
     * 예: 시장가 매수로 SOL 1개를 평균 100.5 USDT에 구매
     * → filledQuoteAmount = 100.5
     * 
     * 데이터베이스: DECIMAL(20, 8), 기본값: 0
     */
    @Column(name = "filled_quote_amount", nullable = false, precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal filledQuoteAmount = BigDecimal.ZERO;
    
    /**
     * 주문 상태
     * Order Status
     * 
     * - 'pending': 대기 중 (체결 안 됨)
     * - 'partial': 부분 체결 (일부만 체결됨)
     * - 'filled': 전량 체결 완료
     * - 'cancelled': 주문 취소됨
     * - 'rejected': 주문 거부됨 (엔진 통신 실패 또는 엔진 거부)
     * 
     * 데이터베이스 제약조건: CHECK (status IN ('pending', 'partial', 'filled', 'cancelled', 'rejected'))
     * 기본값: 'pending'
     */
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "pending";
    
    /**
     * 주문 생성 시간
     * Created Timestamp
     * 
     * 주문이 생성된 시점
     * 데이터베이스에서 자동 생성 (DEFAULT NOW())
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * 주문 정보 마지막 업데이트 시간
     * Updated Timestamp
     * 
     * 주문 상태나 체결 정보가 변경될 때마다 업데이트됨
     * 데이터베이스에서 자동 업데이트 (DEFAULT NOW())
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
