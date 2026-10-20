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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거래내역 업로드 1회. 원본 파일은 저장하지 않고, 읽은 입금 줄(DepositRow)만 저장한다.
 */
@Entity
@Table(name = "deposit_upload")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepositUpload {

    /** deposit_upload.file_name 칸 길이 */
    static final int MAX_FILE_NAME = 300;

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

    public DepositUpload(Long groupBuyId, DepositSource source, String fileName, LocalDateTime uploadedAt) {
        this.groupBuyId = groupBuyId;
        this.source = source;
        // 아주 긴 파일 이름은 잘라서 남긴다 (화면 표시용. 넘기면 업로드가 409 로 실패했음)
        this.fileName = fileName == null || fileName.length() <= MAX_FILE_NAME ? fileName : fileName.substring(0, MAX_FILE_NAME);
        this.uploadedAt = uploadedAt;
    }
}
