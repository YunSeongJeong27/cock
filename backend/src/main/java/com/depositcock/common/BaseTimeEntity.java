package com.depositcock.common;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 모든 테이블 공통: 행 등록 일시·행 수정 일시 (2026-10-21 사용자 요청). 모든 엔티티가 이 클래스를 상속한다.
 * 주문 시각(ordered_at)·최종 변경(customer_order.updated_at)·가입일(seller.created_at) 같은 업무 시각과는 별개로,
 * 그 행이 언제 들어가고 언제 마지막으로 바뀌었는지만 기록한다.
 * <p>
 * JPA 로 저장·수정하면 Spring Data JPA Auditing 이 넣는다 (JpaAuditingConfig, 시각은 앱 Clock = Asia/Seoul).
 * 등록할 때는 둘 다 같은 시각, 수정할 때는 row_updated_at 만 바뀐다.
 * JPA 를 거치지 않는 쓰기(@Query 일괄 UPDATE, JdbcTemplate)는 Auditing 이 못 보므로 그 쿼리에서 row_updated_at 을 직접 넣는다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    /** 행 등록 일시 */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime rowCreatedAt;

    /** 행 수정 일시 (등록할 때는 등록 일시와 같음) */
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime rowUpdatedAt;
}
