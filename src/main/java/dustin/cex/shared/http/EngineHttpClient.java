package dustin.cex.shared.http;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Rust 엔진 HTTP 클라이언트
 * Rust Engine HTTP Client
 * 
 * 역할:
 * - Rust 엔진과의 HTTP 통신
 * - 주문 제출, 취소 등 엔진 명령 전송
 * 
 * 처리 흐름:
 * 1. HTTP 요청 생성 및 전송
 * 2. 엔진에 주문 제출 (동기)
 * 3. 엔진 응답 대기
 * 
 * 주의사항:
 * - 통신 오류는 호출자에게 전달한다. 금융 처리의 거부·취소 확정을 의미하지 않는다.
 * - 타임아웃 설정 (5초)
 * - 연결 재사용 (RestTemplate 기본 동작)
 * 
 * 인증:
 * - Rust 엔진의 주문 생성/취소 API는 인증 불필요 (Java에서 인증 처리)
 * - user_id는 요청 본문(생성) 또는 쿼리 파라미터(취소)로 전달
 */
@Slf4j
@Component
public class EngineHttpClient {
    
    @Value("${engine.http.url:http://localhost:3000}")
    private String engineBaseUrl;
    
    @Value("${engine.http.timeout:5000}")
    private int timeoutMs;
    
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    
    /**
     * 서버 시작 시 HTTP 클라이언트 초기화
     * Initialize HTTP client on server startup
     */
    @PostConstruct
    public void init() {
        // 기존 설정값을 실제 연결·응답 대기에 적용한다. 0은 무제한 대기를 뜻하므로
        // 잘못된 설정으로 요청 스레드가 끝없이 기다리지 않도록 시작부터 거부한다.
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("engine.http.timeout은 양수여야 합니다");
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(requestFactory);
        this.objectMapper = new ObjectMapper();
        // POST 자동 재시도는 추가하지 않는다. 엔진의 영속적인 중복 방지가 갖춰지기
        // 전에는 같은 요청을 다시 보내도 금융 효과가 한 번이라는 보장이 없다.
        // 이 시간은 연결·각 읽기의 제한이며 요청 전체의 절대 마감 시간은 아니다.
    }
    
    /**
     * 주문 제출
     * Submit Order
     * 
     * Rust 엔진에 주문을 제출합니다.
     * 
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param orderType 주문 타입 ("buy" 또는 "sell")
     * @param orderSide 주문 방식 ("limit" 또는 "market")
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @param price 지정가 가격 (지정가 주문만)
     * @param amount 주문 수량
     * @param quoteAmount 시장가 매수 금액 (시장가 매수만)
     * @return 요청 ID·고객 ID와 일치하는 영속 접수 확인 여부. 체결 완료가 아니다.
     * @throws RuntimeException 통신 오류 또는 접수 증명 불일치 시
     */
    public boolean submitOrder(
            Long orderId,
            Long userId,
            String orderType,
            String orderSide,
            String baseMint,
            String quoteMint,
            String price,
            String amount,
            String quoteAmount
    ) {
        try {
            // 요청 본문 생성 (Java에서 생성한 주문 ID 포함)
            String requestBody = buildSubmitOrderRequest(
                    orderId, userId, orderType, orderSide, baseMint, quoteMint, price, amount, quoteAmount
            );
            
            // HTTP 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);
            
            // POST 요청 전송
            String url = engineBaseUrl + "/api/cex/orders";
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );
            
            // 단순한 2xx나 이전 주문 DTO는 영속 접수의 증거가 아니다.
            // 신규(202)·기존(200) 접수만 허용하고 요청 ID·고객·접수 시각을 검증한다.
            int status = response.getStatusCode().value();
            JsonNode receipt = objectMapper.readTree(response.getBody());
            if ((status != 200 && status != 202) || receipt == null || !receipt.isObject()
                    || !receipt.path("order_id").isTextual()
                    || !orderId.toString().equals(receipt.path("order_id").textValue())
                    || !receipt.path("user_id").isTextual()
                    || !userId.toString().equals(receipt.path("user_id").textValue())
                    || !"accepted".equals(receipt.path("receipt_status").textValue())
                    || !receipt.path("accepted_at").isTextual()) {
                throw new IllegalStateException("엔진의 영속 접수 응답을 확인하지 못했습니다");
            }
            OffsetDateTime.parse(receipt.path("accepted_at").textValue());
            return true;

        } catch (RestClientException e) {
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * 주문 취소
     * Cancel Order
     * 
     * Rust 엔진에 주문 취소를 요청합니다.
     * 
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param tradingPair 거래쌍 (예: "SOL/USDT")
     * @return 성공 여부
     * @throws RuntimeException 엔진 통신 실패 시
     */
    public boolean cancelOrder(Long orderId, Long userId, String tradingPair) {
        try {
            // HTTP 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<String> requestEntity = new HttpEntity<>(headers);
            
            // DELETE 요청 전송 (user_id를 쿼리 파라미터로 전달)
            String url = engineBaseUrl + "/api/cex/orders/" + orderId + "?user_id=" + userId;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.DELETE,
                    requestEntity,
                    String.class
            );
            
            // 응답 확인
            if (response.getStatusCode().is2xxSuccessful()) {
                return true;
            } else {
                throw new RuntimeException("엔진 주문 취소 실패: " + response.getStatusCode());
            }
            
        } catch (RestClientException e) {
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * 잔고 동기화
     * Sync balance to engine
     * 
     * Java API 서버에서 잔고 업데이트 시 엔진 메모리 잔고를 동기화합니다.
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류 (예: "SOL", "USDT")
     * @param availableDelta available 증감량 (양수: 입금, 음수: 출금)
     * @return 성공 여부
     * @throws RuntimeException 엔진 통신 실패 시
     */
    public boolean syncBalance(Long userId, String mint, java.math.BigDecimal availableDelta) {
        try {
            // 요청 본문 생성
            String requestBody = buildSyncBalanceRequest(userId, mint, availableDelta);
            
            // HTTP 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);
            
            // POST 요청 전송
            String url = engineBaseUrl + "/api/cex/balances/sync";
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );
            
            // 응답 확인
            if (response.getStatusCode().is2xxSuccessful()) {
                return true;
            } else {
                throw new RuntimeException("엔진 잔고 동기화 실패: " + response.getStatusCode());
            }
            
        } catch (RestClientException e) {
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("엔진 통신 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * 잔고 동기화 요청 본문 생성
     * Build sync balance request body
     */
    private String buildSyncBalanceRequest(Long userId, String mint, java.math.BigDecimal availableDelta) {
        try {
            // JSON 객체 생성
            JsonNode requestNode = objectMapper.createObjectNode()
                    .put("user_id", userId)
                    .put("mint", mint)
                    .put("available_delta", availableDelta.toString());
            
            return objectMapper.writeValueAsString(requestNode);
        } catch (Exception e) {
            throw new RuntimeException("요청 본문 생성 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * 주문 제출 요청 본문 생성
     * Build submit order request body
     * 
     * Java 서버에서 생성한 주문 ID를 Rust 엔진에 전달하여,
     * 엔진이 체결 이벤트를 발행할 때 이 주문 ID를 사용하도록 합니다.
     */
    private String buildSubmitOrderRequest(
            Long orderId,
            Long userId,
            String orderType,
            String orderSide,
            String baseMint,
            String quoteMint,
            String price,
            String amount,
            String quoteAmount
    ) {
        try {
            // JSON 객체 생성 (Java에서 생성한 주문 ID 포함)
            JsonNode requestNode = objectMapper.createObjectNode()
                    .put("order_id", orderId)  // Java에서 생성한 주문 ID 전달
                    .put("user_id", userId)
                    .put("order_type", orderType)
                    .put("order_side", orderSide)
                    .put("base_mint", baseMint)
                    .put("quote_mint", quoteMint != null ? quoteMint : "USDT");
            
            // 지정가 주문인 경우 price 추가
            if (price != null && !price.isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode)
                        .put("price", price);
            }
            
            // 시장가 매수인 경우 quote_amount 추가, 그 외에는 amount 추가
            if ("market".equals(orderSide) && "buy".equals(orderType) && quoteAmount != null && !quoteAmount.isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode)
                        .put("quote_amount", quoteAmount);
            } else if (amount != null && !amount.isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode)
                        .put("amount", amount);
            }
            
            return objectMapper.writeValueAsString(requestNode);
        } catch (Exception e) {
            throw new RuntimeException("요청 본문 생성 실패: " + e.getMessage(), e);
        }
    }
}
