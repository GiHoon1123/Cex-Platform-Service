package dustin.cex.domains.order.model;

import java.math.BigDecimal;

import dustin.cex.domains.order.model.dto.CreateOrderRequest;

/**
 * 고객이 의도한 주문을 검증한 불변 값이다. HTTP DTO와 변경되는 주문 상태를 분리한다.
 * 예약·저장·전송이 이 값 하나를 사용해야 서로 다른 금액이나 자산을 해석하지 않는다.
 * BigDecimal.equals는 scale까지 비교하므로 끝의 0을 제거해 100과 100.00을 같게 만든다.
 */
public record OrderCommand(String orderType, String orderSide, String baseMint, String quoteMint,
                           BigDecimal price, BigDecimal amount, BigDecimal quoteAmount) {
    public OrderCommand {
        if (!"buy".equals(orderType) && !"sell".equals(orderType)) {
            throw new IllegalArgumentException("주문 유형은 buy 또는 sell이어야 합니다");
        }
        if (!"limit".equals(orderSide) && !"market".equals(orderSide)) {
            throw new IllegalArgumentException("주문 방식은 limit 또는 market이어야 합니다");
        }
        quoteMint = quoteMint == null ? "USDT" : quoteMint;
        requireAsset(baseMint);
        requireAsset(quoteMint);
        if (baseMint.equals(quoteMint)) {
            throw new IllegalArgumentException("기준 자산과 결제 자산은 달라야 합니다");
        }

        boolean marketBuy = "buy".equals(orderType) && "market".equals(orderSide);
        if ("limit".equals(orderSide)) {
            price = positiveDecimal(price, "price");
        } else if (price != null) {
            throw new IllegalArgumentException("시장가 주문에는 price를 지정할 수 없습니다");
        }
        if (marketBuy) {
            if (amount != null) {
                throw new IllegalArgumentException("시장가 매수는 amount 대신 quoteAmount를 사용합니다");
            }
            quoteAmount = positiveDecimal(quoteAmount, "quoteAmount");
        } else {
            amount = positiveDecimal(amount, "amount");
            if (quoteAmount != null) {
                throw new IllegalArgumentException("시장가 매수 이외에는 quoteAmount를 지정할 수 없습니다");
            }
        }
        if ("buy".equals(orderType) && !marketBuy) {
            // DB가 price*amount를 반올림하면 전송 금액과 실제 예약이 달라진다.
            // 현재 잔액 컬럼 DECIMAL(30,9)에 정확히 저장할 수 없는 주문은 미리 거부한다.
            positiveDecimal(price.multiply(amount), "예약 금액");
        }
    }

    public static OrderCommand from(CreateOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("주문 요청이 필요합니다");
        }
        return new OrderCommand(request.getOrderType(), request.getOrderSide(), request.getBaseMint(),
                request.getQuoteMint(), request.getPrice(), request.getAmount(), request.getQuoteAmount());
    }

    public String reservationMint() {
        return "buy".equals(orderType) ? quoteMint : baseMint;
    }

    public BigDecimal reservationAmount() {
        return "sell".equals(orderType) ? amount :
                ("market".equals(orderSide) ? quoteAmount : price.multiply(amount));
    }

    private static void requireAsset(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 255) {
            throw new IllegalArgumentException("자산 식별자는 공백 없는 1~255자여야 합니다");
        }
    }

    private static BigDecimal positiveDecimal(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + "는 양수여야 합니다");
        }
        try {
            BigDecimal normalized = value.stripTrailingZeros();
            // setScale(9)로 먼저 확장하면 1E+1000000000 같은 작은 입력이 거대한
            // 정수 할당을 유발한다. 값 자체를 확장하지 않고 scale과 정수 자릿수로
            // DECIMAL(30,9)의 범위를 검사한다. long 계산은 scale 뺄셈 오버플로를 막는다.
            long integerDigits = (long) normalized.precision() - normalized.scale();
            if (normalized.scale() > 9 || integerDigits > 21) {
                throw new ArithmeticException("DECIMAL(30,9) range exceeded");
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + "는 DECIMAL(30,9)에 정확히 저장할 수 있어야 합니다", exception);
        }
    }
}
