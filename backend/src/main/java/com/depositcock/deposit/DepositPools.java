package com.depositcock.deposit;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.order.DepositConfirmation;
import com.depositcock.order.DepositConfirmationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 입금 계좌별 거래내역 묶음 (2026-10-27 사용자 결정 "같은 계좌를 쓰는 공구끼리 입금 연동").
 * 한 판매자 안에서 공구 입금 계좌번호(숫자만)가 같은 공구들이 한 묶음이다. 거래내역은 공구가 아니라 이 묶음에 저장하고,
 * 묶음의 공구 주문 전체와 한 번에 대조한다. 어느 공구에서 확정·처리 완료하든 묶음의 모든 공구에 반영된다.
 */
@Component
@RequiredArgsConstructor
public class DepositPools {

    private final GroupBuyRepository groupBuyRepository;
    private final DepositUploadRepository uploadRepository;
    private final DepositRowRepository rowRepository;
    private final DepositConfirmationRepository confirmationRepository;

    /** 한 판매자의 한 입금 계좌와 그 계좌를 쓰는 공구들 (공구 id 순) */
    public record Pool(Long sellerId, String accountKey, List<GroupBuy> groupBuys) {

        public Set<Long> groupBuyIds() {
            return groupBuys.stream().map(GroupBuy::getId).collect(Collectors.toSet());
        }

        public boolean contains(Long groupBuyId) {
            return groupBuys.stream().anyMatch(g -> g.getId().equals(groupBuyId));
        }
    }

    /** 입금 계좌 키: 공구 입금 계좌번호의 숫자만 ("1001-9964-9421" → "100199649421"). 계좌가 없으면 그 공구 혼자 */
    public static String accountKey(GroupBuy groupBuy) {
        String digits = digits(groupBuy.getAccountNumber());
        return digits.isEmpty() ? "gb:" + groupBuy.getId() : digits;
    }

    static String digits(String accountNumber) {
        return accountNumber == null ? "" : accountNumber.replaceAll("\\D", "");
    }

    public Pool of(GroupBuy groupBuy) {
        Long sellerId = groupBuy.getSeller().getId();
        String key = accountKey(groupBuy);
        List<GroupBuy> same = groupBuyRepository.findBySellerIdOrderByIdDesc(sellerId).stream()
                .filter(g -> accountKey(g).equals(key))
                .sorted(Comparator.comparing(GroupBuy::getId))
                .toList();
        return new Pool(sellerId, key, same);
    }

    /**
     * 공구 입금 계좌를 바꾼 뒤: 예전 계좌를 쓰는 공구가 더 없으면 예전 계좌의 거래내역을 새 계좌로 옮긴다
     * (이 공구에서 올린 거래내역이 사라지지 않게). 다른 공구가 아직 쓰면 그대로 둔다
     */
    public void afterAccountChange(GroupBuy groupBuy, String oldKey) {
        String newKey = accountKey(groupBuy);
        if (oldKey.equals(newKey)) {
            return;
        }
        Long sellerId = groupBuy.getSeller().getId();
        boolean oldStillUsed = groupBuyRepository.findBySellerIdOrderByIdDesc(sellerId).stream()
                .anyMatch(g -> !g.getId().equals(groupBuy.getId()) && accountKey(g).equals(oldKey));
        if (!oldStillUsed) {
            uploadRepository.findBySellerIdAndAccountKeyOrderByIdDesc(sellerId, oldKey).forEach(u -> u.moveTo(newKey));
        }
    }

    /**
     * 공구를 지우기 전 (공구 삭제 = 주문·입금 정보 파기): 이 공구 주문에 확정된 입금 줄은 지운다.
     * 이 계좌를 쓰는 다른 공구가 없으면 계좌의 거래내역 전체를 지운다. 다른 공구가 쓰면 나머지 입금 줄은 그 공구들 것이라 남긴다
     */
    public void beforeDelete(GroupBuy groupBuy, LocalDateTime now) {
        Pool pool = of(groupBuy);
        List<Long> confirmedRows = confirmationRepository.findActiveByGroupBuyIds(Set.of(groupBuy.getId())).stream()
                .map(DepositConfirmation::getDepositRowId).filter(Objects::nonNull).toList();
        boolean shared = pool.groupBuys().size() > 1;
        List<DepositUpload> uploads = shared ? List.of()
                : uploadRepository.findBySellerIdAndAccountKeyOrderByIdDesc(pool.sellerId(), pool.accountKey());
        List<Long> rowIds = shared ? confirmedRows
                : uploads.stream().flatMap(u -> rowRepository.findByUploadId(u.getId()).stream()).map(DepositRow::getId).toList();
        if (!rowIds.isEmpty()) {
            // 지울 입금 줄을 가리키는 확정 기록의 연결을 먼저 비운다 (행 수정 일시를 남기려고)
            confirmationRepository.unlinkDepositRows(rowIds, now);
        }
        if (shared) {
            rowRepository.deleteAllById(confirmedRows);
        } else {
            uploadRepository.deleteAll(uploads);
        }
    }
}
