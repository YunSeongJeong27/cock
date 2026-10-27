package com.depositcock.deposit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Texts;
import com.depositcock.deposit.DepositDtos.BankHeaderRequest;
import com.depositcock.deposit.DepositDtos.BankHeaderSource;
import com.depositcock.deposit.DepositDtos.BankHeaderValues;
import com.depositcock.deposit.DepositDtos.BankHeaderView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 은행별 거래내역 헤더(컬럼)명 (2026-10-27 사용자 결정).
 * <pre>
 * - 운영자 값(bank_header): 운영자가 은행마다 조사해 넣는다. 판매자는 바꾸지 못한다. 기본은 토스뱅크
 * - 판매자 값(seller_bank_header): 운영자 은행의 값을 판매자가 바꾼 것, 또는 판매자가 추가한 은행
 * - 판매자에게 적용되는 값 = 판매자 값이 있으면 판매자 값, 없으면 운영자 값
 * </pre>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BankHeaders {

    private final BankHeaderRepository systemRepository;
    private final SellerBankHeaderRepository sellerRepository;

    /** 판매자에게 적용되는 은행 목록: 운영자 은행(순서대로) + 판매자가 추가한 은행 */
    public List<BankHeaderView> list(Long sellerId) {
        List<SellerBankHeader> own = sellerRepository.findBySellerIdOrderByBankNameAsc(sellerId);
        Map<Long, SellerBankHeader> changed = own.stream().filter(h -> h.getBankHeaderId() != null)
                .collect(Collectors.toMap(SellerBankHeader::getBankHeaderId, Function.identity()));
        List<BankHeaderView> views = new ArrayList<>();
        for (BankHeader system : systemRepository.findAllByOrderBySortOrderAscBankNameAsc()) {
            BankHeaderValues original = values(system);
            SellerBankHeader mine = changed.get(system.getId());
            views.add(new BankHeaderView(system.getBankName(), mine == null ? original : values(mine),
                    mine == null ? BankHeaderSource.SYSTEM : BankHeaderSource.CHANGED, system.isDefaultBank(), original));
        }
        own.stream().filter(h -> h.getBankHeaderId() == null)
                .forEach(h -> views.add(new BankHeaderView(h.getBankName(), values(h), BankHeaderSource.CUSTOM, false, null)));
        return views;
    }

    /** 은행 이름(목록에 보이는 이름 그대로)으로 찾기 */
    public Optional<BankHeaderView> find(Long sellerId, String bankName) {
        String name = bankName == null ? "" : bankName.trim();
        return list(sellerId).stream().filter(v -> v.bankName().equals(name)).findFirst();
    }

    /**
     * 공구 입금 계좌에 적힌 은행 이름으로 고른다 ("국민은행" → KB국민은행, "토스" → 토스뱅크).
     * 운영자 은행은 이름·다른 이름들(aliases)로, 판매자가 추가한 은행은 이름으로 맞춘다. 못 찾으면 기본 은행(토스뱅크)
     */
    public BankHeaderView forAccountBank(Long sellerId, String accountBankName) {
        List<BankHeaderView> views = list(sellerId);
        String key = normalize(accountBankName);
        if (!key.isEmpty()) {
            Map<String, List<String>> aliases = systemRepository.findAll().stream()
                    .collect(Collectors.toMap(BankHeader::getBankName, h -> aliasList(h.getAliases())));
            for (BankHeaderView v : views) {
                boolean byName = normalize(v.bankName()).equals(key);
                boolean byAlias = aliases.getOrDefault(v.bankName(), List.of()).stream().anyMatch(a -> normalize(a).equals(key));
                if (byName || byAlias) {
                    return v;
                }
            }
        }
        return views.stream().filter(BankHeaderView::defaultBank).findFirst().orElse(views.get(0));
    }

    /** 저장: 운영자 은행이면 판매자 값으로 바꾸고(원래 값은 그대로 남음), 목록에 없는 은행이면 판매자 은행으로 추가 */
    @Transactional
    public BankHeaderView save(Long sellerId, BankHeaderRequest r) {
        String bankName = r.bankName().trim();
        Long systemId = systemRepository.findAll().stream().filter(h -> h.getBankName().equals(bankName))
                .map(BankHeader::getId).findFirst().orElse(null);
        SellerBankHeader mine = sellerRepository.findBySellerIdAndBankName(sellerId, bankName)
                .orElseGet(() -> new SellerBankHeader(sellerId, systemId, bankName));
        mine.update(r.nameColumn().trim(), r.amountColumn().trim(), r.dateColumn().trim(),
                Texts.blankToNull(r.accountLabel()), Texts.blankToNull(r.typeColumn()));
        sellerRepository.saveAndFlush(mine);
        return find(sellerId, bankName).orElseThrow();
    }

    /** 판매자 값 지우기: 운영자 은행이면 원래 값으로 돌아가고, 판매자가 추가한 은행이면 목록에서 빠진다 */
    @Transactional
    public void delete(Long sellerId, String bankName) {
        sellerRepository.delete(sellerRepository.findBySellerIdAndBankName(sellerId, bankName == null ? "" : bankName.trim())
                .orElseThrow(() -> new ApiException(ErrorCode.BANK_HEADER_NOT_FOUND)));
    }

    private static BankHeaderValues values(BankHeader h) {
        return new BankHeaderValues(h.getNameColumn(), h.getAmountColumn(), h.getDateColumn(), h.getAccountLabel(),
                h.getTypeColumn());
    }

    private static BankHeaderValues values(SellerBankHeader h) {
        return new BankHeaderValues(h.getNameColumn(), h.getAmountColumn(), h.getDateColumn(), h.getAccountLabel(),
                h.getTypeColumn());
    }

    private static List<String> aliasList(String aliases) {
        return aliases == null ? List.of() : Arrays.stream(aliases.split(",")).map(String::trim).filter(a -> !a.isEmpty()).toList();
    }

    /** 은행 이름 비교용: 띄어쓰기를 빼고 영문은 소문자로 */
    static String normalize(String name) {
        return name == null ? "" : Texts.squash(name).toLowerCase(Locale.ROOT);
    }
}
