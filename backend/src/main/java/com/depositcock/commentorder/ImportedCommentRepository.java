package com.depositcock.commentorder;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportedCommentRepository extends JpaRepository<ImportedComment, Long> {

    List<ImportedComment> findByGroupBuyIdOrderBySeqAsc(Long groupBuyId);

    Optional<ImportedComment> findByIdAndGroupBuyId(Long id, Long groupBuyId);

    @Query("select coalesce(max(c.seq), 0) from ImportedComment c where c.groupBuyId = :groupBuyId")
    int maxSeq(@Param("groupBuyId") Long groupBuyId);

    void deleteByGroupBuyId(Long groupBuyId);
}
