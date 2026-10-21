package dustin.cex.domains.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dustin.cex.domains.order.exception.OrderSubmissionUncertainException;
import dustin.cex.domains.order.model.dto.OrderResponse;
import dustin.cex.domains.order.service.OrderService;

/**
 * 실제 MVC 직렬화·예외 처리 경계를 검증한다. 인증 필터를 대신해 userId 속성을
 * 주입하므로 JWT 보안 자체의 테스트는 아니다. 서비스·DB 테스트와 역할을 나눈다.
 */
class OrderSubmissionResponseTest {
    private MockMvc mvc;
    private OrderService service;
    private static final String REQUEST = """
            {"orderType":"buy","orderSide":"limit","baseMint":"SOL",
             "quoteMint":"USDT","price":"100","amount":"2"}
            """;

    @BeforeEach
    void createController() {
        service = mock(OrderService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service)).build();
    }

    @Test
    void uncertainSubmissionReturnsAcceptedWithExactIdAndQueryLocation() throws Exception {
        // JS 안전 정수 범위를 넘는 ID도 문자열로 손실 없이 전달해야 한다.
        when(service.createOrder(eq(101L), any())).thenThrow(new OrderSubmissionUncertainException(
                9007199254740993L, new RuntimeException("private-engine-address")));
        mvc.perform(post("/api/cex/orders").requestAttr("userId", 101L)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/cex/orders/9007199254740993"))
                .andExpect(jsonPath("$.code").value("ORDER_SUBMISSION_UNCERTAIN"))
                .andExpect(jsonPath("$.orderId").value("9007199254740993"))
                .andExpect(jsonPath("$.statusUrl").value("/api/cex/orders/9007199254740993"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-engine-address"))));
        verify(service, times(1)).createOrder(eq(101L), any());
    }

    @Test
    void confirmedHttpResponseKeepsCreatedContract() throws Exception {
        when(service.createOrder(eq(101L), any())).thenReturn(OrderResponse.builder()
                .order(OrderResponse.OrderDto.builder().id("123").status("pending").build()).build());
        mvc.perform(post("/api/cex/orders").requestAttr("userId", 101L)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.order.id").value("123"));
    }

    @Test
    void missingIdentityDoesNotCreateAnOrder() throws Exception {
        mvc.perform(post("/api/cex/orders").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void malformedOrderDoesNotEnterUncertainSubmissionHandling() throws Exception {
        mvc.perform(post("/api/cex/orders").requestAttr("userId", 101L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void statusLookupPassesAuthenticatedOwnerToService() throws Exception {
        when(service.getOrder(101L, 123L)).thenReturn(OrderResponse.builder()
                .order(OrderResponse.OrderDto.builder().id("123").status("pending").build()).build());
        mvc.perform(get("/api/cex/orders/123").requestAttr("userId", 101L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("pending"));
        verify(service).getOrder(101L, 123L);
    }
}
