package com.depositcock.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.depositcock.comment.ImportedCommentRepository;
import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.customer.Customers;
import com.depositcock.deposit.AliasBook;
import com.depositcock.deposit.DepositRowRepository;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 주문 삭제 (2026-10-19 사용자 요청: 체크해서 여러 건 삭제). 처리가 끝난 주문("완료")만 지울 수 있다.
 * 진행 중인 주문은 먼저 취소·환불·수령 처리를 끝내야 한다 (재고·환불이 꼬이지 않게).
 * 공구 삭제와 같은 방식으로 정리한다:
 * - 이 주문 확정으로 만든·바꾼 입금자명 별칭은 되돌린다
 * - 이 주문에 확정된 입금 줄도 지운다 (남기면 입금 대조에 "관련 없는 입금"으로 다시 뜸)
 * - 댓글 주문이면 그 손님의 저장된 댓글도 지운다 (남기면 [주문으로 반영]을 다시 눌렀을 때 주문이 다시 생김)
 * - 다른 주문이 없는 고객(휴대폰 번호·기기·별칭)은 파기한다
 * 판매 수량(재고)은 바꾸지 않는다: 취소 주문은 취소할 때 이미 돌려놨고, 수령 완료 주문은 실제로 팔린 수량이다.
 * 주문번호는 다시 쓰지 않는다 (GroupBuy.lastOrderNo).
 */
@Service
@RequiredArgsConstructor
public class OrderDeletion {

    private final GroupBuyService groupBuyService;
    private final CustomerOrderRepository orderRepository;
    private final AliasBook aliasBook;
    private final Customers customers;
    private final DepositRowRepository rowRepository;
    private final DepositConfirmationRepository confirmationRepository;
    private final ImportedCommentRepository commentRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    /** @return 지운 주문 수 */
    @Transactional
    public int delete(Long sellerId, Long groupBuyId, List<Long> orderIds) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        Set<Long> ids = new LinkedHashSet<>(orderIds);
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.DELETE_NOTHING_SELECTED);
        }
        // 고른 주문을 한 번에 읽는다 (전에는 주문마다 따로 읽었음, 2026-10-21). 다른 공구 주문 id 가 섞이면 404
        Map<Long, CustomerOrder> found = orderRepository.findByGroupBuyIdAndIdIn(groupBuy.getId(), ids).stream()
                .collect(Collectors.toMap(CustomerOrder::getId, Function.identity()));
        if (found.size() != ids.size()) {
            throw new ApiException(ErrorCode.ORDER_NOT_FOUND);
        }
        List<CustomerOrder> orders = ids.stream().map(found::get).toList();
        List<String> notFinished = orders.stream().filter(o -> !o.isFinished()).map(o -> o.getOrderNo() + "번").toList();
        if (!notFinished.isEmpty()) {
            throw new ApiException(ErrorCode.DELETE_ONLY_FINISHED, String.join(", ", notFinished));
        }

        aliasBook.undoAll(sellerId, orders);
        Set<Long> rowIds = orders.stream().flatMap(o -> o.getConfirmations().stream())
                .filter(DepositConfirmation::isActive).map(DepositConfirmation::getDepositRowId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> nicknames = orders.stream().filter(o -> o.getSource() == OrderSource.COMMENT)
                .map(o -> o.getCustomer().getCommentNickname()).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        LocalDateTime now = LocalDateTime.now(clock);
        orderRepository.deleteAll(orders);
        orderRepository.flush();
        if (!rowIds.isEmpty()) {
            // 지울 입금 줄을 다른 주문의 취소된 확정 이력이 가리키면 연결을 먼저 비운다 (행 수정 일시를 남기려고)
            confirmationRepository.unlinkDepositRows(rowIds, now);
        }
        rowRepository.deleteAllById(rowIds);
        // 같은 닉네임의 댓글 주문이 아직 남아 있으면 댓글은 그대로 둔다
        orderRepository.findByGroupBuyIdAndSource(groupBuy.getId(), OrderSource.COMMENT)
                .forEach(o -> nicknames.remove(o.getCustomer().getCommentNickname()));
        commentRepository.findByGroupBuyIdOrderBySeqAsc(groupBuy.getId()).stream()
                .filter(c -> nicknames.contains(c.getAuthorName().trim()))
                .forEach(commentRepository::delete);
        entityManager.flush();
        customers.deleteWithoutOrders(sellerId, now);
        return orders.size();
    }
}
