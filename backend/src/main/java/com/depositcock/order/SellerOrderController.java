package com.depositcock.order;

import java.util.List;

import com.depositcock.order.OrderDtos.PickedUpRequest;
import com.depositcock.order.OrderDtos.SellerOrder;
import com.depositcock.security.CurrentSeller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seller/group-buys/{groupBuyId}/orders")
public class SellerOrderController {

    private final SellerOrderService service;

    public SellerOrderController(SellerOrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<SellerOrder> list(@PathVariable Long groupBuyId) {
        return service.list(CurrentSeller.id(), groupBuyId);
    }

    @PostMapping("/{orderId}/picked-up")
    public SellerOrder pickedUp(@PathVariable Long groupBuyId, @PathVariable Long orderId,
                                @RequestBody PickedUpRequest request) {
        return service.setPickedUp(CurrentSeller.id(), groupBuyId, orderId, request.pickedUp());
    }

    @PostMapping("/{orderId}/cancel")
    public SellerOrder cancel(@PathVariable Long groupBuyId, @PathVariable Long orderId) {
        return service.cancel(CurrentSeller.id(), groupBuyId, orderId);
    }

    @PostMapping("/{orderId}/refunded")
    public SellerOrder refunded(@PathVariable Long groupBuyId, @PathVariable Long orderId) {
        return service.markRefunded(CurrentSeller.id(), groupBuyId, orderId);
    }
}
