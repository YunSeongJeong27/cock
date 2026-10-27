package com.depositcock.deposit;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Notice;
import com.depositcock.customer.Customer;
import com.depositcock.customer.CustomerRepository;
import com.depositcock.deposit.DepositDtos.AccountCheckView;
import com.depositcock.deposit.DepositDtos.AliasResponse;
import com.depositcock.deposit.DepositDtos.BankHeaderView;
import com.depositcock.deposit.DepositDtos.BankSettingsResponse;
import com.depositcock.deposit.DepositDtos.CandidateView;
import com.depositcock.deposit.DepositDtos.ConfirmItem;
import com.depositcock.deposit.DepositDtos.ConfirmResponse;
import com.depositcock.deposit.DepositDtos.HandledRow;
import com.depositcock.deposit.DepositDtos.MatchResponse;
import com.depositcock.deposit.DepositDtos.MatchRow;
import com.depositcock.deposit.DepositDtos.OrderBrief;
import com.depositcock.deposit.DepositDtos.PreviewResponse;
import com.depositcock.deposit.DepositDtos.UploadResponse;
import com.depositcock.deposit.DepositDtos.UploadSummary;
import com.depositcock.deposit.DepositDtos.UsedRow;
import com.depositcock.deposit.DepositMatcher.Deposit;
import com.depositcock.deposit.DepositMatcher.Known;
import com.depositcock.deposit.DepositMatcher.Outcome;
import com.depositcock.deposit.DepositMatcher.Payee;
import com.depositcock.deposit.DepositMatcher.Relation;
import com.depositcock.deposit.DepositMatcher.Result;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.order.CustomerOrder;
import com.depositcock.order.CustomerOrderRepository;
import com.depositcock.order.DepositConfirmation;
import com.depositcock.order.DepositConfirmationRepository;
import com.depositcock.order.OrderSource;
import com.depositcock.order.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 입금 대조 (정리본 6장, M3): 업로드 → 결과 화면 → 확정/취소 → 별칭 저장.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DepositService {

    private final GroupBuyService groupBuyService;
    private final CustomerOrderRepository orderRepository;
    private final DepositUploadRepository uploadRepository;
    private final DepositRowRepository rowRepository;
    private final DepositorAliasRepository aliasRepository;
    private final AliasBook aliasBook;
    private final DepositConfirmationRepository confirmationRepository;
    private final CustomerRepository customerRepository;
    private final BankHeaders bankHeaders;
    private final DepositPools pools;
    private final BankStatementReader reader;
    private final Clock clock;

    // ---------- 업로드 ----------

    /** 거래내역을 은행별 헤더(컬럼)명으로 읽는다. bankName 이 없으면 공구 입금 계좌의 은행 (2026-10-27) */
    private record Read(BankHeaderView bank, ParsedStatement parsed) {
    }

    private Read read(Long sellerId, GroupBuy groupBuy, MultipartFile file, String bankName, String password) {
        BankHeaderView bank = bankName == null || bankName.isBlank()
                ? bankHeaders.forAccountBank(sellerId, groupBuy.getBankName())
                : bankHeaders.find(sellerId, bankName).orElseThrow(() -> new ApiException(ErrorCode.BANK_HEADER_NOT_FOUND));
        ParsedStatement parsed = reader.read(file.getOriginalFilename(), bytes(file), bank.values().toMapping(),
                bank.values().accountLabel(), password);
        if (!parsed.ok()) {
            // 은행 헤더 설정과 파일이 안 맞으면 대조를 멈춘다 (2026-10-27 사용자 결정)
            throw new ApiException(ErrorCode.STATEMENT_HEADER_MISMATCH, bank.bankName(), String.join(", ", parsed.missingColumns()));
        }
        return new Read(bank, parsed);
    }

    /** 거래내역서의 계좌번호가 공구 입금 계좌와 같은지 (2026-10-27) */
    private static AccountCheckView accountCheck(BankHeaderView bank, ParsedStatement parsed, GroupBuy groupBuy) {
        if (bank.values().accountLabel() == null || groupBuy.getAccountNumber() == null || groupBuy.getAccountNumber().isBlank()) {
            return new AccountCheckView(parsed.accountNumber(), AccountNumbers.Check.UNKNOWN, null);
        }
        AccountNumbers.Check check = AccountNumbers.compare(parsed.accountNumber(), groupBuy.getAccountNumber());
        String notice = switch (check) {
            case SAME -> null;
            case DIFFERENT -> Notice.ACCOUNT_DIFFERENT.text(parsed.accountNumber(), groupBuy.getAccountNumber());
            case UNKNOWN -> Notice.ACCOUNT_NOT_FOUND.text(bank.values().accountLabel());
        };
        return new AccountCheckView(parsed.accountNumber(), check, notice);
    }

    /** password: 암호 걸린 PDF·엑셀의 비밀번호. 파일을 여는 데만 쓰고 저장·기록하지 않는다 (2026-10-27) */
    public PreviewResponse preview(Long sellerId, Long groupBuyId, MultipartFile file, String bankName, String password) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        Read read = read(sellerId, groupBuy, file, bankName, password);
        int duplicates = countDuplicates(pools.of(groupBuy), read.parsed().deposits());
        return new PreviewResponse(trim(read.parsed()), duplicates, read.bank().bankName(),
                accountCheck(read.bank(), read.parsed(), groupBuy));
    }

    @Transactional
    public UploadResponse upload(Long sellerId, Long groupBuyId, MultipartFile file, String bankName, String password) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        Read read = read(sellerId, groupBuy, file, bankName, password);
        String fileName = file.getOriginalFilename();
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        DepositSource source = lower.endsWith(".csv") ? DepositSource.CSV : lower.endsWith(".pdf") ? DepositSource.PDF : DepositSource.EXCEL;
        SaveResult saved = saveDeposits(groupBuy, source, fileName, read.parsed().deposits());
        return new UploadResponse(saved.uploadId(), saved.saved(), saved.duplicates(), trim(read.parsed()), read.bank().bankName(),
                accountCheck(read.bank(), read.parsed(), groupBuy));
    }

    public record SaveResult(Long uploadId, int saved, int duplicates) {
    }

    /** 같은 입금 계좌를 쓰는 공구를 모두 잠근다 (공구 id 순, 2026-10-27). 다른 공구 화면에서 동시에 올리거나 확정해도 순서대로 */
    private DepositPools.Pool lockPool(GroupBuy groupBuy) {
        DepositPools.Pool pool = pools.of(groupBuy);
        pool.groupBuys().forEach(g -> groupBuyService.lockById(g.getId()));
        return pool;
    }

    /**
     * 입금 줄 저장. 같은 입금 계좌에 이미 올라온 같은 입금(이름·금액·시각 원문 같음)은 건너뛴다 (2026-10-27 공구 → 계좌).
     * 개수로 센다: 날짜만 있는 은행 파일에서 같은 사람이 같은 날 같은 금액을 두 번 입금한 경우(주문 2건)
     * 같은 파일 안의 두 줄은 둘 다 저장하고, 같은 파일을 다시 올리면 이미 있는 개수만큼만 건너뛴다.
     */
    @Transactional
    public SaveResult saveDeposits(GroupBuy groupBuy, DepositSource source, String fileName, List<DepositLine> lines) {
        DepositPools.Pool pool = lockPool(groupBuy); // 같은 파일을 동시에 두 번 올려도 중복 검사가 맞게
        Map<String, Integer> existing = existingCounts(pool);
        List<DepositLine> fresh = new ArrayList<>();
        int duplicates = 0;
        for (DepositLine line : lines) {
            if (consume(existing, DepositRow.dedupeKey(line.name(), line.amount(), line.dateText()))) {
                duplicates++;
            } else {
                fresh.add(line);
            }
        }
        // 새 입금이 없으면(같은 파일을 다시 올림 등) 업로드 기록을 만들지 않는다. 전에는 "입금 0건" 파일이 목록에 쌓였음 (2026-10-20 점검)
        if (fresh.isEmpty()) {
            return new SaveResult(null, 0, duplicates);
        }
        DepositUpload upload = uploadRepository.save(new DepositUpload(pool.sellerId(), pool.accountKey(), source, fileName,
                LocalDateTime.now(clock)));
        for (DepositLine line : fresh) {
            rowRepository.save(new DepositRow(upload.getId(), line.rowNo(), line.name(), line.amount(), line.dateText(),
                    line.depositedAt()));
        }
        return new SaveResult(upload.getId(), fresh.size(), duplicates);
    }

    private Map<String, Integer> existingCounts(DepositPools.Pool pool) {
        Map<String, Integer> counts = new HashMap<>();
        rowRepository.findByPool(pool.sellerId(), pool.accountKey()).forEach(r -> counts.merge(r.dedupeKey(), 1, Integer::sum));
        return counts;
    }

    /** 이미 있는 같은 입금이 남아 있으면 하나 차감하고 true */
    private static boolean consume(Map<String, Integer> counts, String key) {
        Integer left = counts.get(key);
        if (left == null || left <= 0) {
            return false;
        }
        counts.put(key, left - 1);
        return true;
    }

    public List<UploadSummary> uploads(Long sellerId, Long groupBuyId) {
        DepositPools.Pool pool = pools.of(groupBuyService.getOwned(sellerId, groupBuyId));
        return uploadSummaries(pool, rowRepository.findByPool(pool.sellerId(), pool.accountKey()), usedRowIds(pool));
    }

    /**
     * 업로드별 입금 줄 수·확정에 쓰인 줄 수. 이미 읽은 입금 줄을 업로드별로 나눈다
     * (전에는 업로드마다 따로 읽었음, 2026-10-20 점검. 대조 화면은 대조에 읽은 줄을 그대로 넘긴다, 2026-10-21)
     */
    private List<UploadSummary> uploadSummaries(DepositPools.Pool pool, List<DepositRow> rows, Set<Long> usedRows) {
        Map<Long, List<DepositRow>> rowsByUpload = rows.stream().collect(Collectors.groupingBy(DepositRow::getUploadId));
        return uploadRepository.findBySellerIdAndAccountKeyOrderByIdDesc(pool.sellerId(), pool.accountKey()).stream().map(u -> {
            List<DepositRow> uploadRows = rowsByUpload.getOrDefault(u.getId(), List.of());
            int used = (int) uploadRows.stream().filter(r -> usedRows.contains(r.getId())).count();
            return new UploadSummary(u.getId(), u.getSource(), u.getFileName(), u.getUploadedAt(), uploadRows.size(), used);
        }).toList();
    }

    @Transactional
    public void deleteUpload(Long sellerId, Long groupBuyId, Long uploadId) {
        DepositPools.Pool pool = lockPool(groupBuyService.getOwned(sellerId, groupBuyId));
        DepositUpload upload = uploadRepository.findByIdAndSellerIdAndAccountKey(uploadId, pool.sellerId(), pool.accountKey())
                .orElseThrow(() -> new ApiException(ErrorCode.UPLOAD_NOT_FOUND));
        Set<Long> usedRows = usedRowIds(pool);
        List<Long> rowIds = rowRepository.findByUploadId(upload.getId()).stream().map(DepositRow::getId).toList();
        if (rowIds.stream().anyMatch(usedRows::contains)) {
            throw new ApiException(ErrorCode.UPLOAD_IN_USE);
        }
        if (!rowIds.isEmpty()) {
            // 취소된 확정 이력이 이 업로드의 입금 줄을 가리키면 연결을 먼저 비운다 (행 수정 일시를 남기려고)
            confirmationRepository.unlinkDepositRows(rowIds, LocalDateTime.now(clock));
        }
        uploadRepository.delete(upload);
    }

    // ---------- 대조 ----------

    /**
     * 대조 (2026-10-27 같은 입금 계좌를 쓰는 공구 전체와 한 번에). 이 공구 화면에는 이 공구 주문과 관련된 입금·관련 없는 입금을
     * 보여 주고, 다른 공구 주문으로 보이는 입금은 otherRows 로 따로 보여 준다
     */
    public MatchResponse match(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        DepositPools.Pool pool = pools.of(groupBuy);
        Map<Long, GroupBuy> groupBuyById = pool.groupBuys().stream().collect(Collectors.toMap(GroupBuy::getId, Function.identity()));
        List<CustomerOrder> orders = orderRepository.findByGroupBuyIdInOrderByGroupBuyIdAscOrderNoAsc(pool.groupBuyIds());
        Map<Long, CustomerOrder> orderById = orders.stream().collect(Collectors.toMap(CustomerOrder::getId, Function.identity()));
        Function<CustomerOrder, OrderBrief> brief = o -> brief(o, groupBuyById);
        List<Payee> payees = orders.stream()
                .filter(o -> o.isActive() && o.isPrepaid() && !o.isPaid())
                .map(DepositService::payee)
                .toList();
        List<DepositRow> rows = rowRepository.findByPool(pool.sellerId(), pool.accountKey());
        Map<Long, Long> orderByRow = new HashMap<>();
        List<DepositConfirmation> confirmations = confirmationRepository.findActiveByGroupBuyIds(pool.groupBuyIds());
        confirmations.stream()
                .filter(c -> c.getDepositRowId() != null)
                .forEach(c -> orderByRow.put(c.getDepositRowId(), c.getOrder().getId()));
        // 대조할 입금 줄: 확정에 안 쓴 것 중 "이미 처리 완료된 입금"으로 옮기지 않은 것 (2026-10-26)
        List<DepositRow> unused = rows.stream().filter(r -> !orderByRow.containsKey(r.getId()) && !r.isHandled()).toList();
        // 거래내역 없이 입금 확정한 주문 (수동 확정·올린 파일을 지운 확정): 나중에 올린 입금 줄을 연결할 수 있다 (2026-10-26)
        Set<Long> unlinkedPaidOrders = confirmations.stream().filter(c -> c.getDepositRowId() == null)
                .map(c -> c.getOrder().getId()).collect(Collectors.toSet());
        List<Deposit> deposits = unused.stream()
                .map(r -> new Deposit(r.getId(), r.getDepositorName(), r.getAmount(), timeOf(r))).toList();

        // 대조 대상은 아니지만, 입금자가 이미 입금 확인된 주문·취소된 주문의 입금자인지 알려주는 데 쓴다
        List<Known> known = orders.stream()
                .filter(o -> o.isPrepaid() && (o.isPaid() || !o.isActive()))
                .map(o -> new Known(payee(o), o.isActive() ? Relation.PAID : Relation.CANCELLED))
                .toList();
        Outcome outcome = DepositMatcher.match(payees, known, deposits, aliasMap(sellerId));
        Map<Long, Payee> payeeById = payees.stream().collect(Collectors.toMap(Payee::orderId, Function.identity()));

        Map<Long, DepositRow> rowById = unused.stream().collect(Collectors.toMap(DepositRow::getId, Function.identity()));
        Long mine = groupBuy.getId();
        List<MatchRow> matchRows = new ArrayList<>();
        List<MatchRow> otherRows = new ArrayList<>();
        int matched = 0;
        int suggested = 0;
        int ambiguous = 0;
        int unrelated = 0;
        for (Result r : outcome.results()) {
            DepositRow row = rowById.get(r.deposit().rowId());
            List<CandidateView> candidates = r.candidates().stream()
                    .map(c -> new CandidateView(brief.apply(orderById.get(c.orderId())), c.evidence().getLabel(), c.amountDiff()))
                    .toList();
            MatchRow view = MatchRow.builder()
                    .rowId(row.getId())
                    .rowNo(row.getRowNo())
                    .depositorName(row.getDepositorName())
                    .amount(row.getAmount())
                    .datetimeText(row.getDatetimeText())
                    .status(r.status())
                    .statusLabel(statusLabel(r))
                    .order(r.orderId() == null ? null : brief.apply(orderById.get(r.orderId())))
                    .evidence(r.evidence() == null ? null : r.evidence().getLabel())
                    .amountDiff(r.amountDiff())
                    .candidates(candidates)
                    .note(note(r.related(), orderById, unlinkedPaidOrders))
                    .refundCheckOrderId(refundCheckOrderId(r.related(), orderById))
                    .linkOrderId(linkOrderId(r.related(), unlinkedPaidOrders))
                    .checkNote(checkNote(outcome.checks().get(row.getId())))
                    .timeNote(r.orderId() == null ? null : timeNote(r.deposit(), payeeById.get(r.orderId())))
                    .build();
            // 이 공구 화면에 보일 입금: 이 공구 주문과 관련 있거나(후보 포함) 어느 주문과도 관련 없는 입금
            boolean related = r.orderId() != null || !r.candidates().isEmpty();
            boolean forMe = !related
                    || (r.orderId() != null && orderById.get(r.orderId()).getGroupBuy().getId().equals(mine))
                    || r.candidates().stream().anyMatch(c -> orderById.get(c.orderId()).getGroupBuy().getId().equals(mine));
            if (!forMe) {
                otherRows.add(view);
                continue;
            }
            matchRows.add(view);
            switch (r.status()) {
                case MATCHED -> matched++;
                case SUGGESTED -> suggested++;
                case AMBIGUOUS -> ambiguous++;
                case UNRELATED -> unrelated++;
            }
        }
        // 확정한 입금: 이 공구 주문에 거래내역 줄로 확정한 것 + 거래내역 없이 수동으로 확정한 것 (주문번호 순, 2026-10-26)
        Map<Long, DepositRow> allRowById = rows.stream().collect(Collectors.toMap(DepositRow::getId, Function.identity()));
        List<UsedRow> used = confirmations.stream()
                .filter(c -> c.getOrder().getGroupBuy().getId().equals(mine))
                .map(c -> {
                    DepositRow r = c.getDepositRowId() == null ? null : allRowById.get(c.getDepositRowId());
                    CustomerOrder o = orderById.get(c.getOrder().getId());
                    return new UsedRow(c.getDepositRowId(), r == null ? c.getDepositorName() : r.getDepositorName(),
                            r == null ? c.getAmount() : r.getAmount(), r == null ? null : r.getDatetimeText(),
                            c.getOrder().getId(), o == null ? 0 : o.getOrderNo(), brief.apply(o), c.isManual());
                })
                .sorted(Comparator.comparingInt(UsedRow::orderNo))
                .toList();
        List<OrderBrief> manualOrders = orders.stream().filter(o -> unlinkedPaidOrders.contains(o.getId()))
                .map(brief).toList();
        List<HandledRow> handled = rows.stream().filter(DepositRow::isHandled)
                .sorted(Comparator.comparing(DepositRow::getHandledAt).reversed())
                .map(r -> new HandledRow(r.getId(), r.getDepositorName(), r.getAmount(), r.getDatetimeText(), r.getHandledAt()))
                .toList();
        List<OrderBrief> unpaid = outcome.unpaid().stream().map(p -> orderById.get(p.orderId()))
                .filter(o -> o.getGroupBuy().getId().equals(mine)).map(brief).toList();
        // 판매자가 직접 고를 수 있는 주문: 이 공구의 모든 미입금 선입금 주문 → 같은 계좌를 쓰는 다른 공구의 미입금 주문
        List<OrderBrief> openOrders = payees.stream().map(p -> orderById.get(p.orderId()))
                .sorted(Comparator.comparing((CustomerOrder o) -> !o.getGroupBuy().getId().equals(mine)))
                .map(brief).toList();
        return MatchResponse.builder()
                .uploads(uploadSummaries(pool, rows, orderByRow.keySet()))
                .rows(matchRows)
                .unpaid(unpaid)
                .openOrders(openOrders)
                .used(used)
                .manualOrders(manualOrders)
                .handled(handled)
                .otherRows(otherRows)
                .sharedGroupBuys(pool.groupBuys().stream().filter(g -> !g.getId().equals(mine)).map(GroupBuy::getTitle).toList())
                .bankName(bankHeaders.forAccountBank(sellerId, groupBuy.getBankName()).bankName())
                .matchedCount(matched)
                .suggestedCount(suggested)
                .ambiguousCount(ambiguous)
                .unrelatedCount(unrelated)
                .build();
    }

    /** 입금 시각: 거래내역에 시:분까지 있을 때만 (날짜만 있으면 1시간 규칙을 볼 수 없음) */
    private static LocalDateTime timeOf(DepositRow r) {
        return r.getDepositedAt() != null && r.getDatetimeText() != null && r.getDatetimeText().contains(":")
                ? r.getDepositedAt() : null;
    }

    /** "주문 12분 뒤 입금", "금액 변경 2시간 5분 뒤 입금", "주문 30분 전 입금" (비교할 수 없으면 null) */
    private static String timeNote(Deposit d, Payee p) {
        Long gap = p == null ? null : DepositMatcher.minutesApart(d, p);
        if (gap == null) {
            return null;
        }
        String base = p.amountSetAt().equals(p.orderedAt()) ? "주문" : "금액 변경";
        return Notice.MATCH_TIME_GAP.text(base, duration(Math.abs(gap)), gap >= 0 ? "뒤" : "전");
    }

    private static String duration(long minutes) {
        if (minutes < 60) {
            return minutes + "분";
        }
        long hours = minutes / 60;
        if (hours >= 48) {
            return (hours / 24) + "일";
        }
        return hours + "시간" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "분");
    }

    /** 관련 없는 입금의 확인 안내 (두 번 보냄·환불 가능성, 거래내역 없이 확정한 주문의 입금) */
    private static String note(DepositMatcher.Related related, Map<Long, CustomerOrder> orderById, Set<Long> unlinkedPaidOrders) {
        if (related == null) {
            return null;
        }
        CustomerOrder o = orderById.get(related.orderId());
        String no = o == null ? "" : o.getOrderNo() + "번 ";
        if (linkOrderId(related, unlinkedPaidOrders) != null) {
            return Notice.DEPOSIT_SAME_AS_MANUAL.text(no);
        }
        return switch (related.relation()) {
            case LINKED -> Notice.DEPOSIT_SAME_AS_LINKED.text(no);
            case PAID -> Notice.DEPOSIT_SAME_AS_PAID.text(no);
            case CANCELLED -> Notice.DEPOSIT_SAME_AS_CANCELLED.text(no);
            case SUGGESTED -> Notice.DEPOSIT_SAME_AS_SUGGESTED.text(no);
        };
    }

    /** 거래내역 없이 입금 확정한 주문(입금 확인됨·취소됨)의 입금자로 보이면 그 주문: 이 입금 줄을 연결하라고 미리 골라 둔다 */
    private static Long linkOrderId(DepositMatcher.Related related, Set<Long> unlinkedPaidOrders) {
        boolean paidOrCancelled = related != null
                && (related.relation() == DepositMatcher.Relation.PAID || related.relation() == DepositMatcher.Relation.CANCELLED);
        return paidOrCancelled && unlinkedPaidOrders.contains(related.orderId()) ? related.orderId() : null;
    }

    /** 확인 필요 안내: 같은 고객의 주문·입금이 여러 건, 입금 시각이 1시간 넘게 차이 (둘 다면 둘 다) */
    private static String checkNote(DepositMatcher.Check check) {
        if (check == null) {
            return null;
        }
        List<String> notes = new ArrayList<>();
        if (check.duplicate()) {
            notes.add(Notice.MATCH_CHECK_NEEDED.text(check.orders(), check.deposits()));
        }
        if (check.minutesApart() != null) {
            notes.add(Notice.MATCH_CHECK_TIME.text(duration(Math.abs(check.minutesApart())),
                    check.minutesApart() >= 0 ? "뒤" : "전"));
        }
        return String.join(" ", notes);
    }

    /**
     * 취소된 주문의 입금자일 때 [환불 필요로 표시]를 걸 주문 (2026-10-19): 입금하고 바로 취소한 고객.
     * 아직 입금 확정·환불 완료가 안 된 선입금 주문만
     */
    private static Long refundCheckOrderId(DepositMatcher.Related related, Map<Long, CustomerOrder> orderById) {
        if (related == null || related.relation() != DepositMatcher.Relation.CANCELLED) {
            return null;
        }
        CustomerOrder o = orderById.get(related.orderId());
        return o != null && !o.isActive() && o.isPrepaid() && !o.isPaid() && o.getRefundedAt() == null ? o.getId() : null;
    }

    private static String statusLabel(Result r) {
        return switch (r.status()) {
            case MATCHED -> Notice.MATCH_AUTO.text();
            case SUGGESTED -> r.amountDiff() == 0 ? Notice.MATCH_SUGGESTED.text()
                    : Notice.MATCH_SUGGESTED_DIFF.text((r.amountDiff() > 0 ? "+" : "") + r.amountDiff());
            case AMBIGUOUS -> Notice.MATCH_AMBIGUOUS.text(r.candidates().size());
            case UNRELATED -> Notice.MATCH_UNRELATED.text();
        };
    }

    /** 판매자가 확정: 주문을 입금 확인됨으로 바꾸고, 입금자명 → 고객 별칭을 저장한다. */
    @Transactional
    public ConfirmResponse confirm(Long sellerId, Long groupBuyId, List<ConfirmItem> items) {
        // 같은 입금 계좌를 쓰는 공구의 입금·주문이면 어느 공구 화면에서든 확정할 수 있다 (2026-10-27)
        DepositPools.Pool pool = lockPool(groupBuyService.getOwned(sellerId, groupBuyId));
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> errors = new ArrayList<>();
        int confirmed = 0;
        Set<Long> usedOrders = new HashSet<>();
        for (ConfirmItem item : items) {
            DepositRow row = rowRepository.findByIdAndPool(item.rowId(), pool.sellerId(), pool.accountKey()).orElse(null);
            CustomerOrder order = orderRepository.findById(item.orderId())
                    .filter(o -> pool.contains(o.getGroupBuy().getId())).orElse(null);
            if (row == null || order == null) {
                errors.add(ErrorCode.DEPOSIT_OR_ORDER_NOT_FOUND.message());
                continue;
            }
            if (confirmationRepository.existsByActiveDepositRowId(row.getId())) {
                errors.add(ErrorCode.DEPOSIT_ALREADY_USED.message(row.getDepositorName(), row.getAmount()));
                continue;
            }
            if (row.isHandled()) {
                errors.add(ErrorCode.DEPOSIT_HANDLED.message(row.getDepositorName(), row.getAmount()));
                continue;
            }
            // 거래내역 없이 입금 확정한 주문에 나중에 올린 입금 줄을 연결 (2026-10-26). 주문 상태·별칭은 그대로 두고 연결만 한다
            DepositConfirmation unlinked = order.activeConfirmation().filter(c -> c.getDepositRowId() == null).orElse(null);
            if (unlinked != null && usedOrders.add(order.getId())) {
                unlinked.linkDepositRow(row.getId());
                orderRepository.flush();
                confirmed++;
                continue;
            }
            // 취소된 주문도 환불 완료 전이면 받는다: 고객이 입금하고 바로 취소한 경우 → 입금 확정 + 환불 필요 (2026-10-19)
            boolean cancelled = !order.isActive();
            if ((cancelled && order.getRefundedAt() != null) || !order.isPrepaid() || order.isPaid()
                    || !usedOrders.add(order.getId())) {
                errors.add(ErrorCode.ORDER_CANNOT_CONFIRM.message(order.getOrderNo()));
                continue;
            }
            DepositConfirmation confirmation = cancelled
                    ? order.confirmPaidAfterCancel(row.getDepositorName(), row.getAmount(), row.getId(), now)
                    : order.confirmPaid(row.getDepositorName(), row.getAmount(), row.getId(), now);
            aliasBook.record(sellerId, order, confirmation, now);
            orderRepository.flush(); // 같은 요청 안의 다음 줄이 이 확정을 보도록 (입금 1건은 주문 1건에만)
            confirmed++;
        }
        return new ConfirmResponse(confirmed, errors);
    }

    /**
     * "이미 처리 완료된 입금"으로 옮기기 (2026-10-26): 대조에서만 빼고 주문·별칭은 건드리지 않는다.
     * 지우지 않으므로 같은 거래내역을 다시 올려도 중복으로 건너뛰고, [되돌리기]로 다시 대조에 넣을 수 있다
     */
    @Transactional
    public void markHandled(Long sellerId, Long groupBuyId, Long rowId, boolean handled) {
        DepositPools.Pool pool = lockPool(groupBuyService.getOwned(sellerId, groupBuyId));
        DepositRow row = rowRepository.findByIdAndPool(rowId, pool.sellerId(), pool.accountKey())
                .orElseThrow(() -> new ApiException(ErrorCode.DEPOSIT_NOT_FOUND));
        if (!handled) {
            row.unmarkHandled();
            return;
        }
        if (confirmationRepository.existsByActiveDepositRowId(row.getId())) {
            throw new ApiException(ErrorCode.DEPOSIT_CONFIRMED_CANNOT_HANDLE);
        }
        if (!row.isHandled()) {
            row.markHandled(LocalDateTime.now(clock));
        }
    }

    /** 입금 확정 취소: 입금 줄을 다시 쓸 수 있게 풀고, 이 확정이 만든·바꾼 별칭을 되돌린다. */
    @Transactional
    public void unconfirm(Long sellerId, Long groupBuyId, Long orderId) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        CustomerOrder order = orderRepository.findByIdAndGroupBuyId(orderId, groupBuy.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isPaid()) {
            throw new ApiException(ErrorCode.NOT_PAID);
        }
        if (order.getRefundedAt() != null) {
            throw new ApiException(ErrorCode.REFUNDED_CANNOT_UNCONFIRM);
        }
        // 환불 신청이 기다리는 동안 확정을 취소하면 "주문 확정 전 + 환불 신청 중"으로 꼬인다. 수령 체크·주문 변경·취소처럼
        // 환불 신청을 먼저 처리하게 한다 (2026-10-26)
        if (order.isRefundPending()) {
            throw new ApiException(ErrorCode.REFUND_PENDING_DECIDE_FIRST);
        }
        aliasBook.undo(sellerId, order, Set.of(order.getId()));
        // 확정 기록은 지우지 않고 취소 시각만 남긴다. 취소된 주문이면 "입금 확정"이 잘못이었다는 뜻이므로 "환불 필요"도 함께 거둔다
        order.cancelPaid(LocalDateTime.now(clock));
    }

    /** 거래내역 없이 수동으로 입금 확정 (판매자가 은행앱으로 직접 확인한 경우) */
    @Transactional
    public void manualConfirm(Long sellerId, Long groupBuyId, Long orderId, String depositorName, int amount) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        CustomerOrder order = orderRepository.findByIdAndGroupBuyId(orderId, groupBuy.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isActive() || !order.isPrepaid() || order.isPaid()) {
            throw new ApiException(ErrorCode.CANNOT_CONFIRM);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        DepositConfirmation confirmation = order.confirmPaid(depositorName.trim(), amount, null, now);
        aliasBook.record(sellerId, order, confirmation, now);
    }

    /** 살아 있는 확정에 쓰인 입금 줄 id (같은 입금 계좌를 쓰는 공구 전체) */
    private Set<Long> usedRowIds(DepositPools.Pool pool) {
        Set<Long> ids = new HashSet<>();
        confirmationRepository.findActiveByGroupBuyIds(pool.groupBuyIds()).forEach(c -> {
            if (c.getDepositRowId() != null) {
                ids.add(c.getDepositRowId());
            }
        });
        return ids;
    }

    private Map<String, Long> aliasMap(Long sellerId) {
        Map<String, Long> map = new HashMap<>();
        aliasRepository.findBySellerId(sellerId).forEach(a -> map.put(a.getDepositorName(), a.getCustomerId()));
        return map;
    }

    public List<AliasResponse> aliases(Long sellerId) {
        List<DepositorAlias> list = aliasRepository.findBySellerId(sellerId);
        Map<Long, Customer> byId = customerRepository.findAllById(list.stream().map(DepositorAlias::getCustomerId).toList())
                .stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        return list.stream()
                .map(a -> new AliasResponse(a.getId(), a.getDepositorName(), a.getCustomerId(),
                        byId.containsKey(a.getCustomerId()) ? byId.get(a.getCustomerId()).label() : "", a.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void deleteAlias(Long sellerId, Long aliasId) {
        aliasRepository.delete(aliasRepository.findByIdAndSellerId(aliasId, sellerId)
                .orElseThrow(() -> new ApiException(ErrorCode.ALIAS_NOT_FOUND)));
    }

    // ---------- 은행 헤더(컬럼) 설정 ----------

    /** 은행 헤더(컬럼) 설정 화면: 은행별 헤더(컬럼)명 + 입금자 별칭 */
    public BankSettingsResponse bankSettings(Long sellerId) {
        return new BankSettingsResponse(bankHeaders.list(sellerId), aliases(sellerId));
    }

    // ---------- 내부 ----------

    private int countDuplicates(DepositPools.Pool pool, List<DepositLine> lines) {
        Map<String, Integer> existing = existingCounts(pool);
        int count = 0;
        for (DepositLine l : lines) {
            if (consume(existing, DepositRow.dedupeKey(l.name(), l.amount(), l.dateText()))) {
                count++;
            }
        }
        return count;
    }

    /** 응답이 너무 커지지 않게 미리보기 입금 줄은 300줄까지만 */
    private static ParsedStatement trim(ParsedStatement p) {
        if (p.deposits().size() <= 300) {
            return p;
        }
        return p.toBuilder().deposits(p.deposits().subList(0, 300)).build();
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REQUIRED);
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_UNREADABLE);
        }
    }

    /** 대조에 쓰는 주문 모양 (대조 대상 주문·이미 입금 확인된 주문·취소된 주문 모두 같음) */
    private static Payee payee(CustomerOrder o) {
        // 댓글 주문은 주문 시각이 댓글 시각이 아니라 반영한 시각이라 1시간 규칙을 보지 않는다 (2026-10-27)
        return new Payee(o.getId(), o.getCustomerId(), o.getDepositorName(), matchNickname(o), o.getExpectedAmount(),
                o.getOrderedAt(), o.getAmountSetAt(), o.getSource() != OrderSource.COMMENT);
    }

    /**
     * 댓글 주문은 닉네임에서 뗀 동·호수도 닉네임처럼 비교한다. 입금자명에 "2171505"처럼 동·호수만 적는 경우가 있어서
     * (2026-10-26 동·호수를 닉네임에서 떼기 전과 같은 결과)
     */
    private static String matchNickname(CustomerOrder o) {
        String unit = o.getUnitAddress();
        if (o.getSource() != OrderSource.COMMENT || unit == null || unit.isBlank()) {
            return o.getNickname();
        }
        String nickname = o.getNickname() == null ? "" : o.getNickname();
        return nickname.contains(unit) ? nickname : (nickname + " " + unit).trim();
    }

    static OrderBrief brief(CustomerOrder o, Map<Long, GroupBuy> groupBuyById) {
        if (o == null) {
            return null;
        }
        GroupBuy g = groupBuyById.get(o.getGroupBuy().getId());
        return new OrderBrief(o.getId(), o.getOrderNo(), o.getDepositorName(), o.getNickname(),
                PhoneNumbers.last4(o.getPhone()), o.getExpectedAmount(), o.getOrderedAt(), o.getGroupBuy().getId(),
                g == null ? null : g.getTitle());
    }
}
