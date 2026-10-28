package com.depositcock.commentorder;

import java.util.List;
import java.util.Map;

import com.depositcock.commentorder.CommentService.CommentInput;
import com.depositcock.commentorder.CommentService.CommentsResponse;
import com.depositcock.security.CurrentSeller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seller/group-buys/{groupBuyId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService service;

    public record SaveRequest(@NotNull(message = "COMMENT_SOURCE_REQUIRED") CommentSource source,
                              @NotNull(message = "COMMENTS_MISSING") List<@NotNull(message = "INVALID_INPUT") CommentInput> comments) {
    }

    public record UpdateRequest(@NotBlank(message = "COMMENT_AUTHOR_REQUIRED") String authorName,
                                @NotNull(message = "COMMENT_CONTENT_MISSING") String content, Integer manualQuantity) {
    }

    public record TextRequest(@NotNull(message = "COMMENT_TEXT_MISSING") String text) {
    }

    public record ApplyRequest(@NotNull(message = "PRODUCT_NOT_CHOSEN") Long productId) {
    }

    @GetMapping
    public CommentsResponse list(@PathVariable Long groupBuyId, @RequestParam(required = false) Long productId) {
        return service.list(CurrentSeller.id(), groupBuyId, productId);
    }

    @PostMapping
    public Map<String, Integer> save(@PathVariable Long groupBuyId, @Valid @RequestBody SaveRequest request) {
        return Map.of("savedCount", service.save(CurrentSeller.id(), groupBuyId, request.source(), request.comments()));
    }

    /** 텍스트를 댓글 목록으로 나눠서 돌려준다 (저장 안 함, 검수 후 저장) */
    @PostMapping("/parse-text")
    public Map<String, List<CommentInput>> parseText(@PathVariable Long groupBuyId, @Valid @RequestBody TextRequest request) {
        // 저장은 안 하지만 남의 공구 경로로는 쓰지 못하게, 댓글 주문을 받지 않는 공구는 미리 막는다
        service.checkCommentUpload(CurrentSeller.id(), groupBuyId);
        return Map.of("comments", CommentService.parseText(request.text()));
    }

    @PutMapping("/{commentId}")
    public void update(@PathVariable Long groupBuyId, @PathVariable Long commentId,
                       @Valid @RequestBody UpdateRequest request) {
        service.update(CurrentSeller.id(), groupBuyId, commentId, request.authorName(), request.content(),
                request.manualQuantity());
    }

    @DeleteMapping("/{commentId}")
    public void delete(@PathVariable Long groupBuyId, @PathVariable Long commentId) {
        service.delete(CurrentSeller.id(), groupBuyId, commentId);
    }

    @DeleteMapping
    public void deleteAll(@PathVariable Long groupBuyId) {
        service.deleteAll(CurrentSeller.id(), groupBuyId);
    }

    @PostMapping("/apply")
    public CommentService.ApplyResult apply(@PathVariable Long groupBuyId, @Valid @RequestBody ApplyRequest request) {
        return service.apply(CurrentSeller.id(), groupBuyId, request.productId());
    }
}
