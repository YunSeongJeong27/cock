package com.depositcock.comment;

import java.util.List;
import java.util.Map;

import com.depositcock.comment.CommentService.CommentInput;
import com.depositcock.comment.CommentService.CommentsResponse;
import com.depositcock.security.CurrentSeller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class CommentController {

    private final CommentService service;

    public CommentController(CommentService service) {
        this.service = service;
    }

    public record SaveRequest(@NotNull CommentSource source, @NotNull List<CommentInput> comments) {
    }

    public record UpdateRequest(@NotBlank String authorName, @NotNull String content, Integer manualQuantity) {
    }

    public record TextRequest(@NotNull String text) {
    }

    public record ApplyRequest(@NotNull Long productId) {
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
        service.checkOwned(CurrentSeller.id(), groupBuyId); // 저장은 안 하지만 남의 공구 경로로는 쓰지 못하게
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
    public Map<String, Integer> apply(@PathVariable Long groupBuyId, @Valid @RequestBody ApplyRequest request) {
        return Map.of("createdCount", service.apply(CurrentSeller.id(), groupBuyId, request.productId()));
    }
}
