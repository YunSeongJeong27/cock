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

/**
 * 2단계: 스크린샷·텍스트로 받은 댓글 (검수 후 저장한 것).
 */
@Entity
@Table(name = "imported_comment")
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

    @Column(nullable = false)
    private String authorName;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private boolean duplicateSuspected;

    /** 판매자가 직접 정한 수량. 있으면 해석 결과보다 우선 */
    private Integer manualQuantity;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected ImportedComment() {
    }

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

    public void update(String authorName, String content, Integer manualQuantity) {
        this.authorName = authorName;
        this.content = content;
        this.manualQuantity = manualQuantity;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupBuyId() {
        return groupBuyId;
    }

    public CommentSource getSource() {
        return source;
    }

    public int getSeq() {
        return seq;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getContent() {
        return content;
    }

    public boolean isDuplicateSuspected() {
        return duplicateSuspected;
    }

    public Integer getManualQuantity() {
        return manualQuantity;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
