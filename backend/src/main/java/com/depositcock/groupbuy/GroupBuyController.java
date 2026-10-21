package com.depositcock.groupbuy;

import java.util.List;

import com.depositcock.groupbuy.GroupBuyDtos.GroupBuyDetail;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuyRequest;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuyResponse;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuySummary;
import com.depositcock.groupbuy.GroupBuyDtos.PauseRequest;
import com.depositcock.order.SellerOrderService;
import com.depositcock.security.CurrentSeller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seller/group-buys")
@RequiredArgsConstructor
public class GroupBuyController {

    private final GroupBuyService groupBuyService;
    private final SellerOrderService sellerOrderService;

    @GetMapping
    public List<GroupBuySummary> list() {
        return groupBuyService.list(CurrentSeller.id());
    }

    @PostMapping
    public GroupBuyResponse create(@Valid @RequestBody GroupBuyRequest request) {
        return groupBuyService.create(CurrentSeller.id(), request);
    }

    @GetMapping("/{id}")
    public GroupBuyResponse get(@PathVariable Long id) {
        return groupBuyService.getResponse(CurrentSeller.id(), id);
    }

    @PutMapping("/{id}")
    public GroupBuyResponse update(@PathVariable Long id, @Valid @RequestBody GroupBuyRequest request) {
        return groupBuyService.update(CurrentSeller.id(), id, request);
    }

    @PostMapping("/{id}/open")
    public GroupBuyResponse open(@PathVariable Long id) {
        return groupBuyService.open(CurrentSeller.id(), id);
    }

    @PostMapping("/{id}/close")
    public GroupBuyResponse close(@PathVariable Long id) {
        return groupBuyService.close(CurrentSeller.id(), id);
    }

    /** 주문 잠시 멈춤 / 다시 받기 */
    @PutMapping("/{id}/pause")
    public GroupBuyResponse pause(@PathVariable Long id, @Valid @RequestBody PauseRequest request) {
        return groupBuyService.pause(CurrentSeller.id(), id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        groupBuyService.delete(CurrentSeller.id(), id);
    }

    /**
     * 공구 상세 화면 한 번에 (공구 + 대시보드 + 주문 목록).
     * 전에는 화면이 공구·대시보드(/dashboard)·주문 목록을 따로 불러서 서버가 같은 주문을 두 번 읽었음 (2026-10-21)
     */
    @GetMapping("/{id}/detail")
    public GroupBuyDetail detail(@PathVariable Long id) {
        return sellerOrderService.detail(CurrentSeller.id(), id);
    }
}
