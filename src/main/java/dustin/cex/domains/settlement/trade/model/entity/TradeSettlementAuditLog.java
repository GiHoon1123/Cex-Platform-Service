package dustin.cex.domains.settlement.trade.model.entity;

import java.time.LocalDate;
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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래 정산 감사 로그 엔티티
 * Trade Settlement Audit Log Entity
 * 
 * 역할:
 * - 정산 관련 모든 작업을 기록하여 감사 추적 가능
 * - 누가, 언제, 무엇을 했는지 기록
 * 
 * 하위 도메인 분리:
 * ================
 * 이 엔티티는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산의 감사 로그만을 담당합니다.
 * 
 * 정산에서의 중요성:
 * ==================
 * 1. 감사 추적:
 *    - 외부 감사 시 모든 작업 내역 제공
 *    - 규정 준수 증명 자료
 * 
 * 2. 보안:
 *    - 비정상적인 작업 탐지
 *    - 권한 없는 접근 추적
 * 
 * 3. 디버깅:
 *    - 문제 발생 시 작업 이력 확인
 *    - 원인 분석 용이
 * 
 * 데이터 구조:
 * ===========
 * - settlement_id: 정산 참조 (TradeSettlement)
 * - action_type: 작업 유형 ('CREATE', 'UPDATE', 'VALIDATE', 'ADJUST', 'DELETE')
 * - action_by: 작업 수행자 (사용자 ID 또는 'SYSTEM')
 * - action_details: 작업 상세 내용 (JSON 형식)
 * - ip_address: 작업 수행 IP 주소
 * - user_agent: 작업 수행 사용자 에이전트
 */
@Entity
@Table(name = "trade_settlement_audit_logs",
       indexes = {
           @Index(name = "idx_trade_settlement_audit_logs_settlement_id", columnList = "settlement_id"),
           @Index(name = "idx_trade_settlement_audit_logs_date", columnList = "settlement_date"),
           @Index(name = "idx_trade_settlement_audit_logs_action_type", columnList = "action_type"),
           @Index(name = "idx_trade_settlement_audit_logs_action_by", columnList = "action_by"),
           @Index(name = "idx_trade_settlement_audit_logs_created_at", columnList = "created_at")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSettlementAuditLog {
    
    /**
     * 감사 로그 고유 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 정산 참조 (nullable: 정산 생성 전 작업도 기록 가능)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id", nullable = true)
    private TradeSettlement settlement;
    
    /**
     * 작업 유형: 'CREATE', 'UPDATE', 'VALIDATE', 'ADJUST', 'DELETE', 'QUERY'
     * 
     * 의미:
     * - 'CREATE': 정산 생성
     * - 'UPDATE': 정산 업데이트
     * - 'VALIDATE': 정산 검증
     * - 'ADJUST': 정산 보정
     * - 'DELETE': 정산 삭제 (일반적으로 불가능하지만 기록)
     * - 'QUERY': 정산 조회 (중요한 조회만 기록)
     */
    @Column(name = "action_type", nullable = false, length = 20)
    private String actionType;
    
    /**
     * 작업 수행자 (사용자 ID 또는 'SYSTEM')
     */
    @Column(name = "action_by", nullable = false, length = 255)
    private String actionBy;
    
    /**
     * 작업 상세 내용 (JSON 형식)
     * 
     * 예시:
     * {
     *   "before": {"totalVolume": 1000, "totalFeeRevenue": 10},
     *   "after": {"totalVolume": 1100, "totalFeeRevenue": 11},
     *   "reason": "누락된 거래 추가"
     * }
     */
    @Column(name = "action_details", columnDefinition = "TEXT")
    private String actionDetails;
    
    /**
     * 작업 수행 IP 주소
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;
    
    /**
     * 작업 수행 사용자 에이전트
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;
    
    /**
     * 정산일 (정산 ID가 없을 때도 조회 가능하도록)
     */
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;
    
    /**
     * 생성 시간
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
