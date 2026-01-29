package dustin.cex.shared.model.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 페이징 응답 DTO
 * Page Response DTO
 * 
 * Spring의 Page 인터페이스를 API 응답으로 사용하기 위한 래퍼 클래스
 * 
 * 사용 예시:
 * ```java
 * Page<Order> orders = orderRepository.findByUserId(userId, pageable);
 * PageResponse<OrderDto> response = PageResponse.of(orders, orderDtoList);
 * ```
 * 
 * @param <T> 페이징할 데이터 타입 (DTO)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    
    /**
     * 현재 페이지의 데이터 목록
     * Current page content
     */
    private List<T> content;
    
    /**
     * 현재 페이지 번호 (0부터 시작)
     * Current page number (0-indexed)
     */
    private int page;
    
    /**
     * 페이지 크기 (한 페이지에 표시할 항목 수)
     * Page size (number of items per page)
     */
    private int size;
    
    /**
     * 전체 항목 수
     * Total number of elements
     */
    private long totalElements;
    
    /**
     * 전체 페이지 수
     * Total number of pages
     */
    private int totalPages;
    
    /**
     * 첫 페이지 여부
     * Whether this is the first page
     */
    private boolean first;
    
    /**
     * 마지막 페이지 여부
     * Whether this is the last page
     */
    private boolean last;
    
    /**
     * 빈 페이지 여부
     * Whether this page is empty
     */
    private boolean empty;
    
    /**
     * Spring Page 객체로부터 PageResponse 생성
     * Create PageResponse from Spring Page object
     * 
     * @param page Spring Page 객체
     * @param content DTO로 변환된 데이터 목록
     * @return PageResponse 인스턴스
     */
    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<?> page, List<T> content) {
        return PageResponse.<T>builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .empty(page.isEmpty())
                .build();
    }
}
