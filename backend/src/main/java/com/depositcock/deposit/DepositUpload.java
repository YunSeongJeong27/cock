package com.depositcock.deposit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 거래내역 업로드 1회. 원본 파일은 저장하지 않고, 읽은 입금 줄(DepositRow)만 저장한다.
 */
@Entity
@Table(name = "deposit_upload")
public class DepositUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long groupBuyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepositSource source;

    private String fileName;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    protected DepositUpload() {
    }

    public DepositUpload(Long groupBuyId, DepositSource source, String fileName, LocalDateTime uploadedAt) {
        this.groupBuyId = groupBuyId;
        this.source = source;
        this.fileName = fileName;
        this.uploadedAt = uploadedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupBuyId() {
        return groupBuyId;
    }

    public DepositSource getSource() {
        return source;
    }

    public String getFileName() {
        return fileName;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
}
