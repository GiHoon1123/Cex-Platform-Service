// =====================================================
// Matcher - 주문 매칭 로직
// =====================================================
// 역할: OrderBook에서 주문을 매칭하고 체결 가능한 주문들을 찾음
// 
// 핵심 알고리즘:
// 1. 가격 우선: 높은 매수가 vs 낮은 매도가
// 2. 시간 우선: 같은 가격이면 먼저 온 주문
// 
// 처리 흐름:
// 1. 신규 주문 받기
// 2. 반대편 호가와 비교 (매수면 매도와, 매도면 매수와)
// 3. 매칭 가능한 주문 찾기
// 4. 체결 실행 (수량 차감)
// 5. MatchResult 반환
//
// 자료구조:
// 1. List<MatchResult>
//    - 매칭 결과를 저장하는 리스트
//    * ArrayList 사용 (추가/접근 O(1))
//    * 매칭 후 Executor로 전달
//    * 장점: 동적 크기 조정, 빠른 접근
//    * 단점/한계:
//      - 동적 배열 재할당 비용 (크기 증가 시)
//      - 메모리 오버헤드 (capacity > size)
//      - Rust Vec와 유사하지만 약간 느림
//
// 2. OrderBook (TreeMap 기반)
//    - 최선가 조회: TreeMap의 firstKey()/lastKey()
//    - 주문 순회: ArrayDeque의 iterator()
//    - 주문 수정: ArrayDeque에서 직접 수정
//    * 단점/한계:
//      - ArrayDeque 순회 시 pointer chasing
//      - 각 주문 접근마다 캐시 미스 가능성
//      - Rust: 구조체가 연속 저장 (캐시 히트 가능성 높음)
//
// 성능:
// - 단일 주문 매칭: O(m) where m=매칭 가능한 주문 수
// - 최악의 경우: O(n*m) where n=가격 레벨 수, m=주문 수
// - 평균: O(m) (대부분 최선가 레벨에서 매칭)
// - Java 한계:
//   * Rust보다 5-10배 느릴 수 있음 (pointer chasing, 캐시 미스)
//   * BigDecimal 연산 비용 (객체 연산)
//   * GC 압력 (임시 객체 생성)
// =====================================================

package dustin.cex.domains.cex.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/**
 * 매칭 엔진
 * OrderBook을 받아서 매칭 로직을 실행
 */
public class Matcher {
    /**
     * 새 Matcher 생성
     */
    public Matcher() {
    }
    
    /**
     * 주문 매칭 실행
     * 
     * @param incomingOrder 새로 들어온 주문 (수량이 변경될 수 있음)
     * @param orderbook 호가창 (주문이 제거/수정됨)
     * @return 체결된 매칭 결과들
     * 
     * 처리 과정:
     * 1. 주문 타입 확인 (buy/sell, limit/market)
     * 2. 반대편 최선가 확인
     * 3. 매칭 가능 여부 판단
     * 4. 매칭 실행 (FIFO, Price-Time Priority)
     * 5. 주문 수량 업데이트
     */
    public List<MatchResult> matchOrder(OrderEntry incomingOrder, OrderBook orderbook) {
        List<MatchResult> matches = new ArrayList<>();
        
        // 주문이 이미 완전히 체결되었으면 종료
        // 시장가 매수는 remaining_quote_amount를 사용하므로, remaining_amount만 확인하면 안 됨
        boolean hasRemaining;
        if (incomingOrder.getRemainingQuoteAmount() != null) {
            // 금액 기반: 남은 금액이 0보다 크면 체결 가능
            hasRemaining = incomingOrder.getRemainingQuoteAmount().compareTo(BigDecimal.ZERO) > 0;
        } else {
            // 수량 기반: 남은 수량이 0보다 크면 체결 가능
            hasRemaining = incomingOrder.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0;
        }
        
        if (!hasRemaining) {
            return matches;
        }
        
        // 주문 타입에 따라 매칭
        String orderType = incomingOrder.getOrderType();
        if ("buy".equals(orderType)) {
            matchBuyOrder(incomingOrder, orderbook, matches);
        } else if ("sell".equals(orderType)) {
            matchSellOrder(incomingOrder, orderbook, matches);
        }
        // 잘못된 타입 무시
        
        return matches;
    }
    
    /**
     * 매수 주문 매칭 (매도 호가와 매칭)
     * 
     * 매칭 조건:
     * - 지정가: 매수 가격 >= 매도 가격
     * - 시장가: 무조건 매칭 (최선 매도가로)
     * 
     * @param buyOrder 매수 주문
     * @param orderbook 호가창
     * @param matches 매칭 결과 리스트
     */
    private void matchBuyOrder(OrderEntry buyOrder, OrderBook orderbook, List<MatchResult> matches) {
        // 매도 호가가 비어있으면 매칭 불가 (정상적인 상황 - 매도 주문이 아직 없을 수 있음)
        BigDecimal bestAsk = orderbook.getBestAsk();
        if (bestAsk == null) {
            return; // 매도 호가 없음
        }
        
        // 지정가 주문: 가격 확인
        if ("limit".equals(buyOrder.getOrderSide())) {
            BigDecimal buyPrice = buyOrder.getPrice();
            if (buyPrice == null) {
                throw new IllegalArgumentException("Limit order must have price");
            }
            if (buyPrice.compareTo(bestAsk) < 0) {
                return; // 매칭 불가 (매수 가격이 낮음)
            }
        }
        // 시장가 주문: 항상 매칭 시도
        
        // 매도 호가 순회 (낮은 가격부터)
        while (true) {
            // 시장가 매수 금액 기반: remaining_quote_amount 확인
            // 수량 기반: remaining_amount 확인
            boolean isFullyFilled;
            if (buyOrder.getRemainingQuoteAmount() != null) {
                // 금액 기반: 남은 금액이 0 이하면 완전 체결
                isFullyFilled = buyOrder.getRemainingQuoteAmount().compareTo(BigDecimal.ZERO) <= 0;
            } else {
                // 수량 기반: 남은 수량이 0이면 완전 체결
                isFullyFilled = buyOrder.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0;
            }
            
            if (isFullyFilled) {
                break;
            }
            
            // 현재 최선 매도가 가져오기
            BigDecimal currentAsk = orderbook.getBestAsk();
            if (currentAsk == null) {
                break; // 더 이상 매도 호가 없음
            }
            
            // 지정가 매수: 가격 재확인
            if ("limit".equals(buyOrder.getOrderSide())) {
                BigDecimal buyPrice = buyOrder.getPrice();
                if (buyPrice.compareTo(currentAsk) < 0) {
                    break; // 더 이상 매칭 불가
                }
            }
            
            // 해당 가격의 매도 주문들 가져오기
            ArrayDeque<OrderEntry> sellOrders = orderbook.getSellOrders().getOrdersAtPrice(currentAsk);
            if (sellOrders == null || sellOrders.isEmpty()) {
                break;
            }
            
            // FIFO: 가장 오래된 주문부터 매칭
            int initialQueueLen = sellOrders.size(); // 초기 큐 길이
            int processedCount = 0; // 처리한 주문 수 (무한 루프 방지)
            
            while (!sellOrders.isEmpty()) {
                OrderEntry sellOrder = sellOrders.pollFirst();
                if (sellOrder == null) {
                    break;
                }
                
                processedCount++;
                
                // 무한 루프 방지: 초기 큐 길이만큼 처리했는데도 매칭이 안 되면 종료
                if (processedCount > initialQueueLen * 2) {
                    // 모든 주문이 같은 user_id일 가능성이 높음
                    sellOrders.addFirst(sellOrder); // 다시 추가
                    break;
                }
                
                // Self-Trade 금지: 같은 유저의 주문은 매칭하지 않음
                if (buyOrder.getUserId() == sellOrder.getUserId()) {
                    // 같은 유저의 주문이므로 다시 큐에 추가하고 다음 주문으로
                    sellOrders.addLast(sellOrder);
                    continue;
                }
                
                // 매칭 수량 계산
                BigDecimal matchAmount;
                if (buyOrder.getRemainingQuoteAmount() != null) {
                    // 시장가 매수 금액 기반: remaining_quote_amount / price로 수량 계산
                    BigDecimal maxAmountFromQuote = buyOrder.getRemainingQuoteAmount()
                        .divide(currentAsk, 18, RoundingMode.HALF_UP);
                    matchAmount = maxAmountFromQuote.min(sellOrder.getRemainingAmount());
                } else {
                    // 수량 기반: 둘 중 작은 것
                    matchAmount = buyOrder.getRemainingAmount().min(sellOrder.getRemainingAmount());
                }
                
                if (matchAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    sellOrders.addFirst(sellOrder); // 다시 추가
                    break; // 더 이상 매칭 불가
                }
                
                // 체결 가격 (Taker가 받아들이는 가격 = 매도 가격)
                BigDecimal matchPrice = currentAsk;
                
                // MatchResult 생성
                MatchResult matchResult = new MatchResult();
                matchResult.setBuyOrderId(buyOrder.getId());
                matchResult.setSellOrderId(sellOrder.getId());
                matchResult.setBuyerId(buyOrder.getUserId());
                matchResult.setSellerId(sellOrder.getUserId());
                matchResult.setPrice(matchPrice);
                matchResult.setAmount(matchAmount);
                matchResult.setBaseMint(buyOrder.getBaseMint());
                matchResult.setQuoteMint(buyOrder.getQuoteMint());
                
                // 주문 수량/금액 차감
                if (buyOrder.getRemainingQuoteAmount() != null) {
                    // 시장가 매수 금액 기반: quote_amount 차감
                    BigDecimal quoteUsed = matchAmount.multiply(matchPrice);
                    buyOrder.setRemainingQuoteAmount(
                        buyOrder.getRemainingQuoteAmount().subtract(quoteUsed)
                    );
                    
                    // amount 업데이트 (매칭된 수량 누적)
                    // 초기 amount는 0이었고, 매칭될 때마다 증가
                    buyOrder.setAmount(buyOrder.getAmount().add(matchAmount));
                    buyOrder.setFilledAmount(buyOrder.getFilledAmount().add(matchAmount));
                    // remaining_amount = amount - filled_amount (자동 계산)
                    buyOrder.setRemainingAmount(
                        buyOrder.getAmount().subtract(buyOrder.getFilledAmount())
                    );
                } else {
                    // 수량 기반: amount 차감
                    buyOrder.setRemainingAmount(
                        buyOrder.getRemainingAmount().subtract(matchAmount)
                    );
                    buyOrder.setFilledAmount(
                        buyOrder.getFilledAmount().add(matchAmount)
                    );
                }
                
                sellOrder.setRemainingAmount(
                    sellOrder.getRemainingAmount().subtract(matchAmount)
                );
                sellOrder.setFilledAmount(
                    sellOrder.getFilledAmount().add(matchAmount)
                );
                
                // 매도 주문이 남아있으면 다시 큐에 추가
                if (sellOrder.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
                    sellOrders.addFirst(sellOrder);
                }
                
                // 매칭 결과 저장
                matches.add(matchResult);
                
                // 매수 주문이 완전히 체결되었는지 확인
                boolean isFullyFilledNow;
                if (buyOrder.getRemainingQuoteAmount() != null) {
                    isFullyFilledNow = buyOrder.getRemainingQuoteAmount().compareTo(BigDecimal.ZERO) <= 0;
                } else {
                    isFullyFilledNow = buyOrder.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0;
                }
                
                if (isFullyFilledNow) {
                    break;
                }
            }
            
            // 해당 가격의 주문이 모두 소진되었는지 확인
            if (sellOrders.isEmpty()) {
                // OrderBook에서 해당 가격 레벨 제거
                orderbook.getSellOrders().removePriceLevel(currentAsk);
            } else {
                // 큐에 남아있는 주문들을 다시 넣어야 함 (이미 처리됨)
                break;
            }
        }
    }
    
    /**
     * 매도 주문 매칭 (매수 호가와 매칭)
     * 
     * 매칭 조건:
     * - 지정가: 매도 가격 <= 매수 가격
     * - 시장가: 무조건 매칭 (최선 매수가로)
     * 
     * @param sellOrder 매도 주문
     * @param orderbook 호가창
     * @param matches 매칭 결과 리스트
     */
    private void matchSellOrder(OrderEntry sellOrder, OrderBook orderbook, List<MatchResult> matches) {
        // 매수 호가가 비어있으면 매칭 불가
        BigDecimal bestBid = orderbook.getBestBid();
        if (bestBid == null) {
            return; // 매수 호가 없음
        }
        
        // 지정가 주문: 가격 확인
        if ("limit".equals(sellOrder.getOrderSide())) {
            BigDecimal sellPrice = sellOrder.getPrice();
            if (sellPrice == null) {
                throw new IllegalArgumentException("Limit order must have price");
            }
            if (sellPrice.compareTo(bestBid) > 0) {
                return; // 매칭 불가 (매도 가격이 높음)
            }
        }
        // 시장가 주문: 항상 매칭 시도
        
        // 매수 호가 순회 (높은 가격부터)
        while (true) {
            // 매도 주문이 완전히 체결되었으면 종료
            if (sellOrder.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0) {
                break;
            }
            
            // 현재 최선 매수가 가져오기
            BigDecimal currentBid = orderbook.getBestBid();
            if (currentBid == null) {
                break; // 더 이상 매수 호가 없음
            }
            
            // 지정가 매도: 가격 재확인
            if ("limit".equals(sellOrder.getOrderSide())) {
                BigDecimal sellPrice = sellOrder.getPrice();
                if (sellPrice.compareTo(currentBid) > 0) {
                    break; // 더 이상 매칭 불가
                }
            }
            
            // 해당 가격의 매수 주문들 가져오기
            ArrayDeque<OrderEntry> buyOrders = orderbook.getBuyOrders().getOrdersAtPrice(currentBid);
            if (buyOrders == null || buyOrders.isEmpty()) {
                break;
            }
            
            // FIFO: 가장 오래된 주문부터 매칭
            int initialQueueLen = buyOrders.size(); // 초기 큐 길이
            int processedCount = 0; // 처리한 주문 수 (무한 루프 방지)
            
            while (!buyOrders.isEmpty()) {
                OrderEntry buyOrder = buyOrders.pollFirst();
                if (buyOrder == null) {
                    break;
                }
                
                processedCount++;
                
                // 무한 루프 방지: 초기 큐 길이만큼 처리했는데도 매칭이 안 되면 종료
                if (processedCount > initialQueueLen * 2) {
                    // 모든 주문이 같은 user_id일 가능성이 높음
                    buyOrders.addFirst(buyOrder); // 다시 추가
                    break;
                }
                
                // Self-Trade 금지: 같은 유저의 주문은 매칭하지 않음
                if (buyOrder.getUserId() == sellOrder.getUserId()) {
                    // 같은 유저의 주문이므로 다시 큐에 추가하고 다음 주문으로
                    buyOrders.addLast(buyOrder);
                    continue;
                }
                
                // 매칭 수량 계산 (둘 중 작은 것)
                BigDecimal matchAmount = sellOrder.getRemainingAmount()
                    .min(buyOrder.getRemainingAmount());
                
                // 체결 가격 (Maker가 제시한 가격 = 매수 가격)
                BigDecimal matchPrice = currentBid;
                
                // MatchResult 생성
                MatchResult matchResult = new MatchResult();
                matchResult.setBuyOrderId(buyOrder.getId());
                matchResult.setSellOrderId(sellOrder.getId());
                matchResult.setBuyerId(buyOrder.getUserId());
                matchResult.setSellerId(sellOrder.getUserId());
                matchResult.setPrice(matchPrice);
                matchResult.setAmount(matchAmount);
                matchResult.setBaseMint(sellOrder.getBaseMint());
                matchResult.setQuoteMint(sellOrder.getQuoteMint());
                
                // 주문 수량 차감
                sellOrder.setRemainingAmount(
                    sellOrder.getRemainingAmount().subtract(matchAmount)
                );
                sellOrder.setFilledAmount(
                    sellOrder.getFilledAmount().add(matchAmount)
                );
                buyOrder.setRemainingAmount(
                    buyOrder.getRemainingAmount().subtract(matchAmount)
                );
                buyOrder.setFilledAmount(
                    buyOrder.getFilledAmount().add(matchAmount)
                );
                
                // 매수 주문이 남아있으면 다시 큐에 추가
                if (buyOrder.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
                    buyOrders.addFirst(buyOrder);
                }
                
                // 매칭 결과 저장
                matches.add(matchResult);
                
                // 매도 주문이 완전히 체결되었으면 종료
                if (sellOrder.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0) {
                    break;
                }
            }
            
            // 해당 가격의 주문이 모두 소진되었는지 확인
            if (buyOrders.isEmpty()) {
                // OrderBook에서 해당 가격 레벨 제거
                orderbook.getBuyOrders().removePriceLevel(currentBid);
            } else {
                // 큐에 남아있는 주문들을 다시 넣어야 함 (이미 처리됨)
                break;
            }
        }
    }
}

