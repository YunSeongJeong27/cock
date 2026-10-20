package com.depositcock.comment;

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
import lombok.Setter;

/**
 * 2단계: 스크린샷·텍스트로 받은 댓글 (검수 후 저장한 것).
 */
@Entity
@Table(name = "imported_comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportedComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long groupBuyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommentSource source;

    /** 시간순 (올린 순서) */
    @Column(nullable = false)
    private int seq;

    @Setter
    @Column(nullable = false)
    private String authorName;

    @Setter
    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private boolean duplicateSuspected;

    /** 판매자가 직접 정한 수량. 있으면 해석 결과보다 우선 */
    @Setter
    private Integer manualQuantity;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ImportedComment(Long groupBuyId, CommentSource source, int seq, String authorName, String content,
                           boolean duplicateSuspected, LocalDateTime createdAt) {
        this.groupBuyId = groupBuyId;
        this.source = source;
        this.seq = seq;
        this.authorName = authorName;
        this.content = content;
        this.duplicateSuspected = duplicateSuspected;
        this.createdAt = createdAt;
    }
}
