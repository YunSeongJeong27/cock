package com.depositcock.deposit;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.depositcock.common.Texts;
import com.depositcock.order.CustomerOrder;
import com.depositcock.order.CustomerOrderRepository;
import com.depositcock.order.DepositConfirmation;
import com.depositcock.order.PaidAliasChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 입금자 별칭(입금자명 → 고객) 기록과 되돌리기.
 * 입금 확정 때 별칭을 새로 만들었는지(CREATED)·바꿨는지(CHANGED)를 주문에 남겨 두고,
 * 확정 취소·공구 삭제 때 그 확정이 한 것만 되돌린다.
 */
@Component
@RequiredArgsConstructor
public class AliasBook {

    private final DepositorAliasRepository aliasRepository;
    private final CustomerOrderRepository orderRepository;

    /** 입금자명 → 고객 별칭을 저장하고, 새로 만들었는지·바꿨는지를 그 확정 기록에 남긴다 */
    public void record(Long sellerId, CustomerOrder order, DepositConfirmation confirmation, LocalDateTime now) {
        String name = Texts.squash(confirmation.getDepositorName());
        if (name.isEmpty()) {
            return;
        }
        Long customerId = order.getCustomerId();
        DepositorAlias alias = aliasRepository.findBySellerIdAndDepositorName(sellerId, name).orElse(null);
        if (alias == null) {
            aliasRepository.save(new DepositorAlias(sellerId, name, customerId, now));
            confirmation.setAliasChange(PaidAliasChange.CREATED);
        } else if (!alias.getCustomerId().equals(customerId)) {
            confirmation.setAliasChange(PaidAliasChange.CHANGED);
            confirmation.setAliasPreviousCustomerId(alias.getCustomerId());
            alias.setCustomerId(customerId);
        }
    }

    /**
     * 이 확정이 만든 별칭은 지우고, 바꾼 별칭은 원래 고객으로 되돌린다. 다음 경우에는 그대로 둔다.
     * - 이 확정이 별칭을 건드리지 않았다 (다른 공구 등에서 이미 같은 고객으로 저장돼 있었음)
     * - 그 뒤에 별칭이 지워졌거나 다른 고객으로 바뀌었다
     * - 같은 입금자명으로 이 고객에게 확정된 다른 주문(ignoredOrderIds 제외)이 아직 있다 (그 확정이 이 별칭의 근거)
     */
    public void undo(Long sellerId, CustomerOrder order, Set<Long> ignoredOrderIds) {
        DepositConfirmation confirmation = order.activeConfirmation().orElse(null);
        if (confirmation == null || confirmation.getAliasChange() == null) {
            return;
        }
        String name = Texts.squash(confirmation.getDepositorName());
        DepositorAlias alias = aliasRepository.findBySellerIdAndDepositorName(sellerId, name).orElse(null);
        if (alias == null || !alias.getCustomerId().equals(order.getCustomerId())) {
            return;
        }
        boolean stillBacked = orderRepository.findOtherPaidOfCustomer(order.getCustomerId(), order.getId())
                .stream()
                .filter(o -> !ignoredOrderIds.contains(o.getId()))
                .anyMatch(o -> o.getPaidDepositorName() != null && Texts.squash(o.getPaidDepositorName()).equals(name));
        if (stillBacked) {
            return;
        }
        if (confirmation.getAliasChange() == PaidAliasChange.CHANGED && confirmation.getAliasPreviousCustomerId() != null) {
            alias.setCustomerId(confirmation.getAliasPreviousCustomerId());
        } else {
            aliasRepository.delete(alias);
        }
    }

    /**
     * 공구 삭제: 그 공구의 입금 확정이 만든·바꾼 별칭을 되돌린다 (개발 전 인터뷰 결과 8번 "공구 삭제 시 그 공구 정보 파기").
     * 나중 확정부터 거꾸로 되돌려야 "만들고 → 바꾼" 순서가 맞게 풀린다.
     */
    public void undoAll(Long sellerId, List<CustomerOrder> orders) {
        Set<Long> ids = orders.stream().map(CustomerOrder::getId).collect(Collectors.toSet());
        orders.stream()
                .filter(CustomerOrder::isPaid)
                .sorted(Comparator.comparing(CustomerOrder::getPaidAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(CustomerOrder::getId).reversed())
                .forEach(o -> undo(sellerId, o, ids));
    }
}
