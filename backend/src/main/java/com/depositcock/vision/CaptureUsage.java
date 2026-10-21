package com.depositcock.vision;

import java.time.LocalDate;

import com.depositcock.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 판매자별·날짜별 비전 AI 로 보낸 캡처 장수 */
@Entity
@Table(name = "capture_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptureUsage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false)
    private int imageCount;

    public CaptureUsage(Long sellerId, LocalDate usageDate) {
        this.sellerId = sellerId;
        this.usageDate = usageDate;
    }

    public void add(int count) {
        this.imageCount += count;
    }
}
