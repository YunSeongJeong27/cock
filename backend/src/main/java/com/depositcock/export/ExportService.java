package com.depositcock.export;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.depositcock.deposit.DepositDtos.AnnouncementLine;
import com.depositcock.deposit.DepositDtos.AnnouncementResponse;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.order.CustomerOrder;
import com.depositcock.order.CustomerOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ExportService {

    private final GroupBuyService groupBuyService;
    private final CustomerOrderRepository orderRepository;
    private final OrderSheetExcelWriter excelWriter;

    public record ExcelFile(String fileName, byte[] content) {
    }

    public ExcelFile orderSheet(Long sellerId, Long groupBuyId, boolean includeContact) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        List<CustomerOrder> orders = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId());
        byte[] content = excelWriter.write(groupBuy, orders, includeContact);
        return new ExcelFile("주문정리표_" + groupBuy.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_") + ".xlsx", content);
    }

    /**
     * 입금 확인 문구 (정리본 3장 F) + 입금 확인된 사람 목록 (G, 밴드에서 한 명씩 @태그하며 체크용).
     *
     * @param since 이 시각 이후에 확정된 것만 (null 이면 전부)
     */
    public AnnouncementResponse announcement(Long sellerId, Long groupBuyId, LocalDateTime since) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        List<AnnouncementLine> lines = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()).stream()
                .filter(o -> o.isActive() && o.isPaid())
                .filter(o -> since == null || (o.getPaidAt() != null && !o.getPaidAt().isBefore(since)))
                .sorted(Comparator.comparing(CustomerOrder::getPaidAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(o -> new AnnouncementLine(o.getId(), o.getOrderNo(), displayName(o), o.getNickname(),
                        o.getDepositorName(), itemsText(o), o.getPaidAt(), o.isPickedUp()))
                .toList();
        StringBuilder text = new StringBuilder();
        text.append("[").append(groupBuy.getTitle()).append("] 입금 확인 안내\n");
        if (lines.isEmpty()) {
            text.append("아직 입금 확인된 주문이 없습니다.");
        } else {
            text.append("아래 분들 입금 확인되었습니다. 감사합니다 :)\n");
            for (AnnouncementLine l : lines) {
                text.append("✅ ").append(l.displayName()).append(" ").append(l.itemsText()).append(" 입금 확인\n");
            }
            text.append("총 ").append(lines.size()).append("건");
        }
        return new AnnouncementResponse(text.toString().trim(), lines);
    }

    private static String displayName(CustomerOrder o) {
        return o.getNickname() != null && !o.getNickname().isBlank() ? o.getNickname() : o.getDepositorName();
    }

    private static String itemsText(CustomerOrder o) {
        return o.getItems().stream().map(i -> i.getProductNameSnapshot() + " " + i.getQuantity() + "개")
                .collect(Collectors.joining(", "));
    }
}
