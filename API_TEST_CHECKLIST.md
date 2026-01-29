# 조회 API 테스트 체크리스트

## 테스트 전 확인사항
- [ ] Java 서버 재시작 완료
- [ ] Rust 엔진 실행 중 (`http://localhost:3002/api/health`)
- [ ] 데이터베이스 연결 정상
- [ ] 테스트용 사용자 및 주문 데이터 존재

---

## 1. 주문 조회 API 테스트

### 1.1 단일 주문 조회
```bash
# JWT 토큰 필요
curl -X GET "http://localhost:8080/api/cex/orders/{orderId}" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

**예상 응답:**
```json
{
  "order": {
    "id": "123",
    "userId": 1,
    "orderType": "buy",
    "orderSide": "limit",
    "baseMint": "SOL",
    "quoteMint": "USDT",
    "price": 100.5,
    "amount": 1.0,
    "filledAmount": 0.0,
    "filledQuoteAmount": 0.0,
    "status": "pending",
    "createdAt": "2026-01-29T10:30:00",
    "updatedAt": "2026-01-29T10:30:00"
  }
}
```

### 1.2 내 주문 목록 조회
```bash
curl -X GET "http://localhost:8080/api/cex/orders/my?status=pending&limit=10&offset=0" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

**예상 응답:** OrderDto 배열

---

## 2. 오더북 조회 API 테스트

### 2.1 오더북 조회 (인증 불필요)
```bash
curl -X GET "http://localhost:8080/api/cex/orderbook?baseMint=SOL&quoteMint=USDT&depth=20"
```

**예상 응답:**
```json
{
  "bids": [
    {
      "id": "123",
      "userId": 1,
      "orderType": "buy",
      "orderSide": "limit",
      "baseMint": "SOL",
      "quoteMint": "USDT",
      "price": 100.5,
      "amount": 1.0,
      ...
    }
  ],
  "asks": [
    {
      "id": "124",
      "userId": 2,
      "orderType": "sell",
      "orderSide": "limit",
      "baseMint": "SOL",
      "quoteMint": "USDT",
      "price": 101.0,
      "amount": 1.0,
      ...
    }
  ]
}
```

**확인 사항:**
- [ ] bids는 가격 내림차순 정렬 (높은 가격부터)
- [ ] asks는 가격 오름차순 정렬 (낮은 가격부터)
- [ ] depth 파라미터가 정상 작동하는지

---

## 3. 체결 내역 조회 API 테스트

### 3.1 거래쌍별 체결 내역 조회 (인증 불필요)
```bash
curl -X GET "http://localhost:8080/api/cex/trades?baseMint=SOL&quoteMint=USDT&limit=50"
```

**예상 응답:**
```json
[
  {
    "id": 456,
    "buyOrderId": 123,
    "sellOrderId": 124,
    "buyerId": 1,
    "sellerId": 2,
    "baseMint": "SOL",
    "quoteMint": "USDT",
    "price": 100.5,
    "amount": 1.0,
    "createdAt": "2026-01-29T10:30:00"
  }
]
```

**확인 사항:**
- [ ] 최신 체결 내역부터 정렬되는지
- [ ] limit 파라미터가 정상 작동하는지

### 3.2 내 체결 내역 조회
```bash
curl -X GET "http://localhost:8080/api/cex/trades/my?mint=SOL&limit=50&offset=0" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

**확인 사항:**
- [ ] 본인이 매수자 또는 매도자로 참여한 거래만 조회되는지
- [ ] mint 파라미터로 필터링이 정상 작동하는지
- [ ] 페이지네이션이 정상 작동하는지

---

## 4. 포지션 조회 API 테스트

### 4.1 모든 자산 포지션 조회
```bash
curl -X GET "http://localhost:8080/api/cex/positions" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

**예상 응답:**
```json
[
  {
    "mint": "SOL",
    "currentBalance": "11.0",
    "available": "10.0",
    "locked": "1.0",
    "averageEntryPrice": 100.5,
    "currentMarketPrice": 110.0,
    "currentValue": 1210.0,
    "unrealizedPnl": 100.0,
    "unrealizedPnlPercent": 10.0,
    "tradeSummary": {
      "totalBuyTrades": 5,
      "totalSellTrades": 2,
      "realizedPnl": 50.0
    }
  }
]
```

**확인 사항:**
- [ ] 포지션 수량이 0이 아닌 자산만 조회되는지
- [ ] 미실현 손익 계산이 정확한지
- [ ] 거래 요약 정보가 정확한지

### 4.2 특정 자산 포지션 조회
```bash
curl -X GET "http://localhost:8080/api/cex/positions/SOL?quoteMint=USDT" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

**확인 사항:**
- [ ] 포지션이 없으면 404 반환하는지
- [ ] 포지션 정보가 정확한지

---

## 5. 잔고 조회 API 테스트

### 5.1 모든 잔고 조회
```bash
curl -X GET "http://localhost:8080/api/cex/balances" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

**예상 응답:**
```json
[
  {
    "userId": 1,
    "mintAddress": "SOL",
    "available": 10.0,
    "locked": 1.0,
    "total": 11.0
  },
  {
    "userId": 1,
    "mintAddress": "USDT",
    "available": 1000.0,
    "locked": 100.0,
    "total": 1100.0
  }
]
```

**확인 사항:**
- [ ] 모든 자산 잔고가 조회되는지
- [ ] total = available + locked 인지

### 5.2 특정 자산 잔고 조회
```bash
curl -X GET "http://localhost:8080/api/cex/balances/SOL" \
  -H "Authorization: Bearer {JWT_TOKEN}"
```

**확인 사항:**
- [ ] 잔고가 없으면 404 반환하는지
- [ ] 잔고 정보가 정확한지

---

## 6. Swagger UI 확인

### 6.1 Swagger UI 접속
```
http://localhost:8080/swagger-ui.html
```

**확인 사항:**
- [ ] 모든 조회 API가 Swagger에 표시되는지
- [ ] 예시값이 제대로 표시되는지
- [ ] Try it out 기능이 정상 작동하는지
- [ ] 응답 예시가 정확한지

---

## 테스트 완료 후

모든 API가 정상 작동하면:
1. ✅ Java 조회 API 구현 완료 확인
2. 다음 단계: Rust에서 조회 API 삭제
