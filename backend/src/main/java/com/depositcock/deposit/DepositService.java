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
import com.depositcock.common.Texts;
import com.depositcock.customer.Customer;
import com.depositcock.customer.CustomerRepository;
import com.depositcock.deposit.DepositDtos.AliasResponse;
import com.depositcock.deposit.DepositDtos.BankSettingsResponse;
import com.depositcock.deposit.DepositDtos.CandidateView;
import com.depositcock.deposit.DepositDtos.ConfirmItem;
import com.depositcock.deposit.DepositDtos.ConfirmResponse;
import com.depositcock.deposit.DepositDtos.DefaultColumns;
import com.depositcock.deposit.DepositDtos.MatchResponse;
import com.depositcock.deposit.DepositDtos.MatchRow;
import com.depositcock.deposit.DepositDtos.OrderBrief;
import com.depositcock.deposit.DepositDtos.PresetRequest;
import com.depositcock.deposit.DepositDtos.PresetResponse;
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
    private final BankColumnPresetRepository presetRepository;
    private final BankStatementReader reader;
    private final Clock clock;

    // ---------- 업로드 ----------

    public PreviewResponse preview(Long sellerId, Long groupBuyId, MultipartFile file, ColumnMapping mapping, Long presetId) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        ResolvedMapping resolved = resolveMapping(sellerId, mapping, presetId);
        ParsedStatement parsed = reader.read(file.getOriginalFilename(), bytes(file), resolved.mapping());
        int duplicates = countDuplicates(groupBuy.getId(), parsed.deposits());
        return new PreviewResponse(trim(parsed), duplicates, resolved.presetName());
    }

    @Transactional
    public UploadResponse upload(Long sellerId, Long groupBuyId, MultipartFile file, ColumnMapping mapping, Long presetId) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        ResolvedMapping resolved = resolveMapping(sellerId, mapping, presetId);
        String fileName = file.getOriginalFilename();
        ParsedStatement parsed = reader.read(fileName, bytes(file), resolved.mapping());
        if (!parsed.ok()) {
            throw ApiException.withMessage(ErrorCode.STATEMENT_COLUMNS_NOT_FOUND, parsed.message());
        }
        DepositSource source = fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? DepositSource.CSV : DepositSource.EXCEL;
        SaveResult saved = saveDeposits(groupBuy.getId(), source, fileName, parsed.deposits());
        return new UploadResponse(saved.uploadId(), saved.saved(), saved.duplicates(), trim(parsed));
    }

    public record SaveResult(Long uploadId, int saved, int duplicates) {
    }

    /**
     * 입금 줄 저장. 같은 공구에 이미 올라온 같은 입금(이름·금액·시각 원문 같음)은 건너뛴다.
     * 개수로 센다: 날짜만 있는 은행 파일에서 같은 사람이 같은 날 같은 금액을 두 번 입금한 경우(주문 2건)
     * 같은 파일 안의 두 줄은 둘 다 저장하고, 같은 파일을 다시 올리면 이미 있는 개수만큼만 건너뛴다.
     */
    @Transactional
    public SaveResult saveDeposits(Long groupBuyId, DepositSource source, String fileName, List<DepositLine> lines) {
        groupBuyService.lockById(groupBuyId); // 같은 파일을 동시에 두 번 올려도 중복 검사가 맞게
        Map<String, Integer> existing = existingCounts(groupBuyId);
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
        DepositUpload upload = uploadRepository.save(new DepositUpload(groupBuyId, source, fileName, LocalDateTime.now(clock)));
        for (DepositLine line : fresh) {
            rowRepository.save(new DepositRow(upload.getId(), line.rowNo(), line.name(), line.amount(), line.dateText(),
                    line.depositedAt()));
        }
        return new SaveResult(upload.getId(), fresh.size(), duplicates);
    }

    private Map<String, Integer> existingCounts(Long groupBuyId) {
        Map<String, Integer> counts = new HashMap<>();
        rowRepository.findByGroupBuyId(groupBuyId).forEach(r -> counts.merge(r.dedupeKey(), 1, Integer::sum));
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
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        return uploadSummaries(groupBuy.getId(), rowRepository.findByGroupBuyId(groupBuy.getId()), usedRowIds(groupBuy.getId()));
    }

    /**
     * 업로드별 입금 줄 수·확정에 쓰인 줄 수. 이미 읽은 공구의 입금 줄을 업로드별로 나눈다
     * (전에는 업로드마다 따로 읽었음, 2026-10-20 점검. 대조 화면은 대조에 읽은 줄을 그대로 넘긴다, 2026-10-21)
     */
    private List<UploadSummary> uploadSummaries(Long groupBuyId, List<DepositRow> rows, Set<Long> usedRows) {
        Map<Long, List<DepositRow>> rowsByUpload = rows.stream().collect(Collectors.groupingBy(DepositRow::getUploadId));
        return uploadRepository.findByGroupBuyIdOrderByIdDesc(groupBuyId).stream().map(u -> {
            List<DepositRow> uploadRows = rowsByUpload.getOrDefault(u.getId(), List.of());
            int used = (int) uploadRows.stream().filter(r -> usedRows.contains(r.getId())).count();
            return new UploadSummary(u.getId(), u.getSource(), u.getFileName(), u.getUploadedAt(), uploadRows.size(), used);
        }).toList();
    }

    @Transactional
    public void deleteUpload(Long sellerId, Long groupBuyId, Long uploadId) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        DepositUpload upload = uploadRepository.findByIdAndGroupBuyId(uploadId, groupBuy.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.UPLOAD_NOT_FOUND));
        Set<Long> usedRows = usedRowIds(groupBuy.getId());
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

    public MatchResponse match(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        List<CustomerOrder> orders = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId());
        Map<Long, CustomerOrder> orderById = orders.stream().collect(Collectors.toMap(CustomerOrder::getId, Function.identity()));
        List<Payee> payees = orders.stream()
                .filter(o -> o.isActive() && o.isPrepaid() && !o.isPaid())
                .map(DepositService::payee)
                .toList();
        List<DepositRow> rows = rowRepository.findByGroupBuyId(groupBuy.getId());
        Map<Long, Long> orderByRow = new HashMap<>();
        List<DepositConfirmation> confirmations = confirmationRepository.findActiveByGroupBuyId(groupBuy.getId());
        confirmations.stream()
                .filter(c -> c.getDepositRowId() != null)
                .forEach(c -> orderByRow.put(c.getDepositRowId(), c.getOrder().getId()));
        List<DepositRow> unused = rows.stream().filter(r -> !orderByRow.containsKey(r.getId())).toList();
        List<Deposit> deposits = unused.stream().map(r -> new Deposit(r.getId(), r.getDepositorName(), r.getAmount())).toList();

        // 대조 대상은 아니지만, 입금자가 이미 입금 확인된 주문·취소된 주문의 입금자인지 알려주는 데 쓴다
        List<Known> known = orders.stream()
                .filter(o -> o.isPrepaid() && (o.isPaid() || !o.isActive()))
                .map(o -> new Known(payee(o), o.isActive() ? Relation.PAID : Relation.CANCELLED))
                .toList();
        Outcome outcome = DepositMatcher.match(payees, known, deposits, aliasMap(sellerId));

        Map<Long, DepositRow> rowById = unused.stream().collect(Collectors.toMap(DepositRow::getId, Function.identity()));
        List<MatchRow> matchRows = new ArrayList<>();
        int matched = 0;
        int suggested = 0;
        int ambiguous = 0;
        int unrelated = 0;
        for (Result r : outcome.results()) {
            DepositRow row = rowById.get(r.deposit().rowId());
            switch (r.status()) {
                case MATCHED -> matched++;
                case SUGGESTED -> suggested++;
                case AMBIGUOUS -> ambiguous++;
                case UNRELATED -> unrelated++;
            }
            List<CandidateView> candidates = r.candidates().stream()
                    .map(c -> new CandidateView(brief(orderById.get(c.orderId())), c.evidence().getLabel(), c.amountDiff()))
                    .toList();
            matchRows.add(MatchRow.builder()
                    .rowId(row.getId())
                    .rowNo(row.getRowNo())
                    .depositorName(row.getDepositorName())
                    .amount(row.getAmount())
                    .datetimeText(row.getDatetimeText())
                    .status(r.status())
                    .statusLabel(statusLabel(r))
                    .order(r.orderId() == null ? null : brief(orderById.get(r.orderId())))
                    .evidence(r.evidence() == null ? null : r.evidence().getLabel())
                    .amountDiff(r.amountDiff())
                    .candidates(candidates)
                    .note(note(r.related(), orderById))
                    .refundCheckOrderId(refundCheckOrderId(r.related(), orderById))
                    .build());
        }
        // 확정한 입금: 거래내역 줄로 확정한 것 + 거래내역 없이 수동으로 확정한 것 (주문번호 순, 2026-10-26)
        Map<Long, DepositRow> allRowById = rows.stream().collect(Collectors.toMap(DepositRow::getId, Function.identity()));
        List<UsedRow> used = confirmations.stream()
                .map(c -> {
                    DepositRow r = c.getDepositRowId() == null ? null : allRowById.get(c.getDepositRowId());
                    CustomerOrder o = orderById.get(c.getOrder().getId());
                    return new UsedRow(c.getDepositRowId(), r == null ? c.getDepositorName() : r.getDepositorName(),
                            r == null ? c.getAmount() : r.getAmount(), r == null ? null : r.getDatetimeText(),
                            c.getOrder().getId(), o == null ? 0 : o.getOrderNo(), brief(o), c.isManual());
                })
                .sorted(Comparator.comparingInt(UsedRow::orderNo))
                .toList();
        List<OrderBrief> unpaid = outcome.unpaid().stream().map(p -> brief(orderById.get(p.orderId()))).toList();
        // 판매자가 직접 고를 수 있는 주문: 다른 입금에 추천된 주문까지 포함한 모든 미입금 선입금 주문
        List<OrderBrief> openOrders = payees.stream().map(p -> brief(orderById.get(p.orderId()))).toList();
        return MatchResponse.builder()
                .uploads(uploadSummaries(groupBuy.getId(), rows, orderByRow.keySet()))
                .rows(matchRows)
                .unpaid(unpaid)
                .openOrders(openOrders)
                .used(used)
                .matchedCount(matched)
                .suggestedCount(suggested)
                .ambiguousCount(ambiguous)
                .unrelatedCount(unrelated)
                .build();
    }

    /** 관련 없는 입금의 확인 안내 (중복 입금·환불 가능성) */
    private static String note(DepositMatcher.Related related, Map<Long, CustomerOrder> orderById) {
        if (related == null) {
            return null;
        }
        CustomerOrder o = orderById.get(related.orderId());
        String no = o == null ? "" : o.getOrderNo() + "번 ";
        return switch (related.relation()) {
            case LINKED -> Notice.DEPOSIT_SAME_AS_LINKED.text(no);
            case PAID -> Notice.DEPOSIT_SAME_AS_PAID.text(no);
            case CANCELLED -> Notice.DEPOSIT_SAME_AS_CANCELLED.text(no);
            case SUGGESTED -> Notice.DEPOSIT_SAME_AS_SUGGESTED.text(no);
        };
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
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> errors = new ArrayList<>();
        int confirmed = 0;
        Set<Long> usedOrders = new HashSet<>();
        for (ConfirmItem item : items) {
            DepositRow row = rowRepository.findByIdAndGroupBuyId(item.rowId(), groupBuy.getId()).orElse(null);
            CustomerOrder order = orderRepository.findByIdAndGroupBuyId(item.orderId(), groupBuy.getId()).orElse(null);
            if (row == null || order == null) {
                errors.add(ErrorCode.DEPOSIT_OR_ORDER_NOT_FOUND.message());
                continue;
            }
            if (confirmationRepository.existsByActiveDepositRowId(row.getId())) {
                errors.add(ErrorCode.DEPOSIT_ALREADY_USED.message(row.getDepositorName(), row.getAmount()));
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

    /** 살아 있는 확정에 쓰인 입금 줄 id */
    private Set<Long> usedRowIds(Long groupBuyId) {
        Set<Long> ids = new HashSet<>();
        confirmationRepository.findActiveByGroupBuyId(groupBuyId).forEach(c -> {
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

    // ---------- 열 이름 프리셋 ----------

    public List<PresetResponse> presets(Long sellerId) {
        return presetRepository.findBySellerIdOrderByNameAsc(sellerId).stream().map(PresetResponse::of).toList();
    }

    /** 은행 열 설정 화면: 자동 탐지 열 이름 후보(화면 안내용) + 저장된 열 설정 + 입금자 별칭 */
    public BankSettingsResponse bankSettings(Long sellerId) {
        DefaultColumns defaults = new DefaultColumns(BankStatementReader.DATE_CANDIDATES, BankStatementReader.NAME_CANDIDATES,
                BankStatementReader.AMOUNT_CANDIDATES, BankStatementReader.TYPE_CANDIDATES);
        return new BankSettingsResponse(defaults, presets(sellerId), aliases(sellerId));
    }

    /** 같은 이름이 있으면 덮어쓴다. */
    @Transactional
    public PresetResponse savePreset(Long sellerId, PresetRequest r) {
        ColumnMapping mapping = new ColumnMapping(Texts.blankToNull(r.dateColumn()), r.nameColumn().trim(),
                r.amountColumn().trim(), Texts.blankToNull(r.typeColumn()));
        BankColumnPreset preset = presetRepository.findBySellerIdAndName(sellerId, r.name().trim())
                .map(p -> {
                    p.update(mapping);
                    return p;
                })
                .orElseGet(() -> presetRepository.save(new BankColumnPreset(sellerId, r.name().trim(), mapping,
                        LocalDateTime.now(clock))));
        return PresetResponse.of(preset);
    }

    @Transactional
    public void deletePreset(Long sellerId, Long presetId) {
        presetRepository.delete(presetRepository.findByIdAndSellerId(presetId, sellerId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRESET_NOT_FOUND)));
    }

    // ---------- 내부 ----------

    private record ResolvedMapping(ColumnMapping mapping, String presetName) {
    }

    private ResolvedMapping resolveMapping(Long sellerId, ColumnMapping mapping, Long presetId) {
        if (presetId != null) {
            BankColumnPreset preset = presetRepository.findByIdAndSellerId(presetId, sellerId)
                    .orElseThrow(() -> new ApiException(ErrorCode.PRESET_NOT_FOUND));
            return new ResolvedMapping(preset.toMapping(), preset.getName());
        }
        return new ResolvedMapping(mapping != null && mapping.isComplete() ? mapping : null, null);
    }

    private int countDuplicates(Long groupBuyId, List<DepositLine> lines) {
        Map<String, Integer> existing = existingCounts(groupBuyId);
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
        return new Payee(o.getId(), o.getCustomerId(), o.getDepositorName(), matchNickname(o), o.getExpectedAmount(),
                o.getOrderedAt());
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

    static OrderBrief brief(CustomerOrder o) {
        if (o == null) {
            return null;
        }
        return new OrderBrief(o.getId(), o.getOrderNo(), o.getDepositorName(), o.getNickname(),
                PhoneNumbers.last4(o.getPhone()), o.getExpectedAmount(), o.getOrderedAt());
    }
}
