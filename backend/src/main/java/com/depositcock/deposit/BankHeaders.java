package com.depositcock.deposit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * 은행·파일 종류(PDF·엑셀)별 거래내역 헤더(컬럼)명 (2026-10-27 사용자 결정).
 * <pre>
 * - 운영자 값(bank + bank_header): 운영자가 은행마다 조사해 넣는다. 판매자는 바꾸지 못한다. 기본 은행은 토스뱅크
 * - 판매자 값(seller_bank_header): 운영자 값을 판매자가 바꾼 것, 또는 판매자가 추가한 은행·파일 종류
 * - 판매자에게 적용되는 값 = 판매자 값이 있으면 판매자 값, 없으면 운영자 값
 * </pre>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BankHeaders {

    private final BankRepository bankRepository;
    private final BankHeaderRepository systemRepository;
    private final SellerBankHeaderRepository sellerRepository;

    /** 판매자에게 적용되는 목록: 운영자 은행(순서대로, 은행 안에서 PDF → 엑셀) + 판매자가 추가한 값 */
    public List<BankHeaderView> list(Long sellerId) {
        List<SellerBankHeader> own = sellerRepository.findBySellerIdOrderByBankNameAscFileTypeAsc(sellerId);
        Map<Long, SellerBankHeader> changed = own.stream().filter(h -> h.getBankHeaderId() != null)
                .collect(Collectors.toMap(SellerBankHeader::getBankHeaderId, Function.identity()));
        // 파일 종류는 PDF → 엑셀 순 (enum 순서. DB 정렬은 글자 순이라 쓰지 않음)
        Map<Long, List<BankHeader>> headersByBank = systemRepository.findAll().stream()
                .sorted(Comparator.comparing(BankHeader::getFileType))
                .collect(Collectors.groupingBy(BankHeader::getBankId));
        List<BankHeaderView> views = new ArrayList<>();
        for (Bank bank : bankRepository.findAllByOrderBySortOrderAscBankNameAsc()) {
            for (BankHeader system : headersByBank.getOrDefault(bank.getId(), List.of())) {
                BankHeaderValues original = values(system);
                SellerBankHeader mine = changed.get(system.getId());
                views.add(new BankHeaderView(bank.getBankName(), system.getFileType(), mine == null ? original : values(mine),
                        mine == null ? BankHeaderSource.SYSTEM : BankHeaderSource.CHANGED, bank.isDefaultBank(), original));
            }
        }
        own.stream().filter(h -> h.getBankHeaderId() == null)
                .sorted(Comparator.comparing(SellerBankHeader::getBankName).thenComparing(SellerBankHeader::getFileType))
                .forEach(h -> views.add(new BankHeaderView(h.getBankName(), h.getFileType(), values(h), BankHeaderSource.CUSTOM,
                        false, null)));
        return views;
    }

    /** 판매자가 고를 수 있는 은행 이름: 운영자 은행(순서대로) + 판매자가 추가한 은행 */
    public List<String> bankNames(Long sellerId) {
        Set<String> names = new LinkedHashSet<>();
        bankRepository.findAllByOrderBySortOrderAscBankNameAsc().forEach(b -> names.add(b.getBankName()));
        sellerRepository.findBySellerIdOrderByBankNameAscFileTypeAsc(sellerId).forEach(h -> names.add(h.getBankName()));
        return List.copyOf(names);
    }

    /** 은행 이름(목록에 보이는 이름 그대로)·파일 종류로 찾기 */
    public Optional<BankHeaderView> find(Long sellerId, String bankName, StatementType fileType) {
        String name = bankName == null ? "" : bankName.trim();
        return list(sellerId).stream().filter(v -> v.bankName().equals(name) && v.fileType() == fileType).findFirst();
    }

    /**
     * 공구 입금 계좌에 적힌 은행 이름으로 은행을 고른다 ("국민은행" → KB국민은행, "토스" → 토스뱅크).
     * 운영자 은행은 이름·다른 이름들(aliases)로, 판매자가 추가한 은행은 이름으로 맞춘다. 못 찾으면 기본 은행(토스뱅크)
     */
    public String bankFor(Long sellerId, String accountBankName) {
        List<Bank> banks = bankRepository.findAllByOrderBySortOrderAscBankNameAsc();
        String key = normalize(accountBankName);
        if (!key.isEmpty()) {
            for (Bank bank : banks) {
                boolean byAlias = aliasList(bank.getAliases()).stream().anyMatch(a -> normalize(a).equals(key));
                if (normalize(bank.getBankName()).equals(key) || byAlias) {
                    return bank.getBankName();
                }
            }
            for (String name : bankNames(sellerId)) {
                if (normalize(name).equals(key)) {
                    return name;
                }
            }
        }
        return banks.stream().filter(Bank::isDefaultBank).map(Bank::getBankName).findFirst()
                .orElseGet(() -> bankNames(sellerId).get(0));
    }

    /** 저장: 운영자 값이 있는 은행·파일 종류면 판매자 값으로 바꾸고(원래 값은 그대로 남음), 없으면 판매자 값으로 추가 */
    @Transactional
    public BankHeaderView save(Long sellerId, BankHeaderRequest r) {
        String bankName = r.bankName().trim();
        Long systemId = bankRepository.findAll().stream().filter(b -> b.getBankName().equals(bankName)).findFirst()
                .flatMap(bank -> systemRepository.findAll().stream()
                        .filter(h -> h.getBankId().equals(bank.getId()) && h.getFileType() == r.fileType()).findFirst())
                .map(BankHeader::getId).orElse(null);
        SellerBankHeader mine = sellerRepository.findBySellerIdAndBankNameAndFileType(sellerId, bankName, r.fileType())
                .orElseGet(() -> new SellerBankHeader(sellerId, systemId, bankName, r.fileType()));
        mine.update(r.nameColumn().trim(), r.amountColumn().trim(), r.dateColumn().trim(),
                Texts.blankToNull(r.accountLabel()), Texts.blankToNull(r.typeColumn()));
        sellerRepository.saveAndFlush(mine);
        return find(sellerId, bankName, r.fileType()).orElseThrow();
    }

    /** 판매자 값 지우기: 운영자 값이 있으면 원래 값으로 돌아가고, 판매자가 추가한 값이면 목록에서 빠진다 */
    @Transactional
    public void delete(Long sellerId, String bankName, StatementType fileType) {
        sellerRepository.delete(sellerRepository.findBySellerIdAndBankNameAndFileType(sellerId,
                        bankName == null ? "" : bankName.trim(), fileType)
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
