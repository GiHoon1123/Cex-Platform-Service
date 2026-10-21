package dustin.cex.domains.order.model;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import dustin.cex.domains.order.model.dto.CreateOrderRequest;

/** 주문 검증은 DB 부작용 전에 끝나야 하므로 외부 의존성 없는 값 객체로 시험한다. */
class OrderCommandTest {
    @Test
    void equivalentDecimalsAndDefaultQuoteProduceSameCommand() {
        var first = OrderCommand.from(limitBuy());
        var equivalent = limitBuy();
        equivalent.setQuoteMint("USDT");
        equivalent.setPrice(new BigDecimal("100.00"));
        equivalent.setAmount(new BigDecimal("2.000"));
        assertThat(OrderCommand.from(equivalent)).isEqualTo(first);
        assertThat(first.reservationMint()).isEqualTo("USDT");
        assertThat(first.reservationAmount()).isEqualByComparingTo("200");
    }

    @Test
    void marketBuyKeepsMissingAmountAndExactQuoteBudget() {
        var request = limitBuy();
        request.setOrderSide("market");
        request.setPrice(null);
        request.setAmount(null);
        request.setQuoteAmount(new BigDecimal("123.250000001"));
        var command = OrderCommand.from(request);
        assertThat(command.amount()).isNull();
        assertThat(command.reservationAmount()).isEqualByComparingTo("123.250000001");
    }

    @Test
    void marketSellReservesBaseAmount() {
        var request = limitBuy();
        request.setOrderType("sell");
        request.setOrderSide("market");
        request.setPrice(null);
        var command = OrderCommand.from(request);
        assertThat(command.reservationMint()).isEqualTo("SOL");
        assertThat(command.reservationAmount()).isEqualByComparingTo("2");
    }

    static Stream<CreateOrderRequest> invalidCommands() {
        return Stream.<java.util.function.Consumer<CreateOrderRequest>>of(
                r -> r.setOrderType("BUY"), r -> r.setOrderSide("stop"),
                r -> r.setBaseMint(" SOL"), r -> r.setBaseMint("USDT"),
                r -> r.setQuoteMint(""), r -> r.setAmount(BigDecimal.ZERO),
                r -> r.setPrice(null), r -> r.setQuoteAmount(BigDecimal.ONE),
                r -> r.setAmount(new BigDecimal("0.0000000001")),
                r -> r.setPrice(new BigDecimal("1000000000000000000000")),
                r -> { r.setPrice(new BigDecimal("1.000000001")); r.setAmount(new BigDecimal("1.000000001")); },
                r -> { r.setOrderSide("market"); r.setQuoteAmount(BigDecimal.ONE); },
                r -> { r.setOrderType("sell"); r.setQuoteAmount(BigDecimal.ONE); }
        ).map(change -> { var request = limitBuy(); change.accept(request); return request; });
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void invalidOrUnrepresentableCommandIsRejectedBeforeReservation(CreateOrderRequest request) {
        assertThatThrownBy(() -> OrderCommand.from(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marketBuyCannotCarryBaseAmount() {
        assertThatThrownBy(() -> new OrderCommand("buy", "market", "SOL", "USDT", null,
                BigDecimal.ONE, BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+1000000000", "1E-1000000000"})
    void hugeExponentIsRejectedWithoutExpandingTheNumber(String value) {
        var request = limitBuy();
        request.setPrice(new BigDecimal(value));
        assertThatThrownBy(() -> OrderCommand.from(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999999999999.123456789", "79228162514264337593.543950336"})
    void rustDecimalUnrepresentableInputIsRejectedBeforeReservation(String value) {
        var request = limitBuy();
        request.setPrice(BigDecimal.ONE);
        request.setAmount(new BigDecimal(value));
        assertThatThrownBy(() -> OrderCommand.from(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactDecimalBoundaryAndTrailingZerosAreAccepted() {
        var request = limitBuy();
        request.setPrice(BigDecimal.ONE);
        request.setAmount(new BigDecimal("79228162514264337593.5439503350"));
        assertThat(OrderCommand.from(request).amount()).isEqualByComparingTo("79228162514264337593.543950335");
    }

    @Test
    void unrepresentableReservationProductIsRejectedBeforeReservation() {
        var request = limitBuy();
        request.setPrice(new BigDecimal("99999999999.999999999"));
        request.setAmount(new BigDecimal("9999999999"));
        assertThatThrownBy(() -> OrderCommand.from(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitSellWithInexactQuoteValueIsRejectedLikeLimitBuy() {
        var request = limitBuy();
        request.setOrderType("sell");
        request.setPrice(new BigDecimal("0.000000001"));
        request.setAmount(new BigDecimal("0.1"));
        assertThatThrownBy(() -> OrderCommand.from(request)).isInstanceOf(IllegalArgumentException.class);
    }

    private static CreateOrderRequest limitBuy() {
        return CreateOrderRequest.builder().orderType("buy").orderSide("limit").baseMint("SOL")
                .price(new BigDecimal("100")).amount(new BigDecimal("2")).build();
    }
}
