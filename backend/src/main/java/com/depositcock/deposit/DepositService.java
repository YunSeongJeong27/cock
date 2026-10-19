package com.depositcock.deposit;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.depositcock.common.ApiException;
import com.depositcock.deposit.DepositDtos.AliasResponse;
import com.depositcock.deposit.DepositDtos.CandidateView;
import com.depositcock.deposit.DepositDtos.ConfirmItem;
import com.depositcock.deposit.DepositDtos.ConfirmResponse;
import com.depositcock.deposit.DepositDtos.MatchResponse;
import com.depositcock.deposit.DepositDtos.MatchRow;
import com.depositcock.deposit.DepositDtos.OrderBrief;
import com.depositcock.deposit.DepositDtos.PresetRequest;
import com.depositcock.deposit.DepositDtos.PresetResponse;
import com.depositcock.deposit.DepositDtos.PreviewResponse;
import com.depositcock.deposit.DepositDtos.UploadResponse;
import com.depositcock.deposit.DepositDtos.UploadSummary;
import com.depositcock.deposit.DepositDtos.UsedRow;
import com.depositcock.customer.Customer;
import com.depositcock.customer.CustomerRepository;
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
import com.depositcock.order.PhoneNumbers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 입금 대조 (정리본 6장, M3): 업로드 → 결과 화면 → 확정/취소 → 별칭 저장.
 */
@Service
@Transactional(readOnly = true)
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

    public DepositService(GroupBuyService groupBuyService, CustomerOrderRepository orderRepository,
                          DepositUploadRepository uploadRepository, DepositRowRepository rowRepository,
                          DepositorAliasRepository aliasRepository, AliasBook aliasBook,
                          DepositConfirmationRepository confirmationRepository, CustomerRepository customerRepository, BankColumnPresetRepository presetRepository,
                          BankStatementReader reader, Clock clock) {
        this.groupBuyService = groupBuyService;
        this.orderRepository = orderRepository;
        this.uploadRepository = uploadRepository;
        this.rowRepository = rowRepository;
        this.aliasRepository = aliasRepository;
        this.aliasBook = aliasBook;
        this.confirmationRepository = confirmationRepository;
        this.customerRepository = customerRepository;
        this.presetRepository = presetRepository;
        this.reader = reader;
        this.clock = clock;
    }

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
            throw ApiException.badRequest(parsed.message());
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
        DepositUpload upload = uploadRepository.save(new DepositUpload(groupBuyId, source, fileName, LocalDateTime.now(clock)));
        int saved = 0;
        int duplicates = 0;
        for (DepositLine line : lines) {
            DepositRow row = new DepositRow(upload.getId(), line.rowNo(), line.name(), line.amount(), line.dateText(),
                    line.depositedAt());
            if (consume(existing, row.dedupeKey())) {
                duplicates++;
                continue;
            }
            rowRepository.save(row);
            saved++;
        }
        return new SaveResult(upload.getId(), saved, duplicates);
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
        Set<Long> usedRows = usedRowIds(groupBuy.getId());
        return uploadRepository.findByGroupBuyIdOrderByIdDesc(groupBuy.getId()).stream().map(u -> {
            List<DepositRow> rows = rowRepository.findByUploadId(u.getId());
            int used = (int) rows.stream().filter(r -> usedRows.contains(r.getId())).count();
            return new UploadSummary(u.getId(), u.getSource(), u.getFileName(), u.getUploadedAt(), rows.size(), used);
        }).toList();
    }

    @Transactional
    public void deleteUpload(Long sellerId, Long groupBuyId, Long uploadId) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        DepositUpload upload = uploadRepository.findByIdAndGroupBuyId(uploadId, groupBuy.getId())
                .orElseThrow(() -> ApiException.notFound("업로드를 찾을 수 없습니다."));
        Set<Long> usedRows = usedRowIds(groupBuy.getId());
        boolean used = rowRepository.findByUploadId(upload.getId()).stream().anyMatch(r -> usedRows.contains(r.getId()));
        if (used) {
            throw ApiException.badRequest("확정에 쓴 입금이 들어 있는 업로드는 지울 수 없습니다. 먼저 해당 입금 확정을 취소해 주세요.");
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
                .map(o -> new Payee(o.getId(), o.getCustomerId(), o.getDepositorName(), o.getNickname(),
                        o.getExpectedAmount(), o.getOrderedAt()))
                .toList();
        List<DepositRow> rows = rowRepository.findByGroupBuyId(groupBuy.getId());
        Map<Long, Long> orderByRow = new HashMap<>();
        confirmationRepository.findActiveByGroupBuyId(groupBuy.getId()).stream()
                .filter(c -> c.getDepositRowId() != null)
                .forEach(c -> orderByRow.put(c.getDepositRowId(), c.getOrder().getId()));
        List<DepositRow> unused = rows.stream().filter(r -> !orderByRow.containsKey(r.getId())).toList();
        List<Deposit> deposits = unused.stream().map(r -> new Deposit(r.getId(), r.getDepositorName(), r.getAmount())).toList();

        // 대조 대상은 아니지만, 입금자가 이미 입금 확인된 주문·취소된 주문의 입금자인지 알려주는 데 쓴다
        List<Known> known = orders.stream()
                .filter(o -> o.isPrepaid() && (o.isPaid() || !o.isActive()))
                .map(o -> new Known(new Payee(o.getId(), o.getCustomerId(), o.getDepositorName(), o.getNickname(),
                        o.getExpectedAmount(), o.getOrderedAt()), o.isActive() ? Relation.PAID : Relation.CANCELLED))
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
            matchRows.add(new MatchRow(row.getId(), row.getRowNo(), row.getDepositorName(), row.getAmount(),
                    row.getDatetimeText(), r.status(), statusLabel(r), r.orderId() == null ? null : brief(orderById.get(r.orderId())),
                    r.evidence() == null ? null : r.evidence().getLabel(), r.amountDiff(), candidates,
                    note(r.related(), orderById), refundCheckOrderId(r.related(), orderById)));
        }
        List<UsedRow> used = rows.stream().filter(r -> orderByRow.containsKey(r.getId()))
                .map(r -> {
                    Long orderId = orderByRow.get(r.getId());
                    CustomerOrder o = orderById.get(orderId);
                    return new UsedRow(r.getId(), r.getDepositorName(), r.getAmount(), r.getDatetimeText(),
                            orderId, o == null ? 0 : o.getOrderNo());
                }).toList();
        List<OrderBrief> unpaid = outcome.unpaid().stream().map(p -> brief(orderById.get(p.orderId()))).toList();
        // 판매자가 직접 고를 수 있는 주문: 다른 입금에 추천된 주문까지 포함한 모든 미입금 선입금 주문
        List<OrderBrief> openOrders = payees.stream().map(p -> brief(orderById.get(p.orderId()))).toList();
        return new MatchResponse(matchRows, unpaid, openOrders, used, matched, suggested, ambiguous, unrelated);
    }

    /** 관련 없는 입금의 확인 안내 (중복 입금·환불 가능성) */
    private static String note(DepositMatcher.Related related, Map<Long, CustomerOrder> orderById) {
        if (related == null) {
            return null;
        }
        CustomerOrder o = orderById.get(related.orderId());
        String no = o == null ? "" : o.getOrderNo() + "번 ";
        return switch (related.relation()) {
            case LINKED -> no + "주문에 같은 입금자의 입금이 이미 연결됐습니다. 중복 입금인지 확인하세요.";
            case PAID -> no + "주문(입금 확인됨)의 입금자와 같습니다. 중복 입금인지 확인하세요.";
            case CANCELLED -> "취소된 " + no + "주문의 입금자와 같습니다. 환불이 필요한지 확인하세요.";
            case SUGGESTED -> no + "주문에 같은 입금자의 다른 입금이 추천됐습니다. 나눠 보냈거나 중복 입금인지 확인하세요.";
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
            case MATCHED -> "자동 매칭";
            case SUGGESTED -> r.amountDiff() == 0 ? "추천" : "추천 (금액 차이 " + (r.amountDiff() > 0 ? "+" : "") + r.amountDiff() + "원)";
            case AMBIGUOUS -> "후보 " + r.candidates().size() + "명 - 직접 선택";
            case UNRELATED -> "관련 없는 입금";
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
                errors.add("입금 또는 주문을 찾을 수 없습니다.");
                continue;
            }
            if (confirmationRepository.existsByActiveDepositRowId(row.getId())) {
                errors.add("'" + row.getDepositorName() + " " + row.getAmount() + "원' 입금은 이미 다른 주문에 확정되었습니다.");
                continue;
            }
            // 취소된 주문도 환불 완료 전이면 받는다: 고객이 입금하고 바로 취소한 경우 → 입금 확정 + 환불 필요 (2026-10-19)
            boolean cancelled = !order.isActive();
            if ((cancelled && order.getRefundedAt() != null) || !order.isPrepaid() || order.isPaid()
                    || !usedOrders.add(order.getId())) {
                errors.add(order.getOrderNo() + "번 주문은 입금 확정할 수 없는 상태입니다.");
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
                .orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
        if (!order.isPaid()) {
            throw ApiException.badRequest("입금 확정된 주문이 아닙니다.");
        }
        if (order.getRefundedAt() != null) {
            throw ApiException.badRequest("환불 완료된 주문은 입금 확정을 취소할 수 없습니다.");
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
                .orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
        if (!order.isActive() || !order.isPrepaid() || order.isPaid()) {
            throw ApiException.badRequest("입금 확정할 수 없는 상태입니다.");
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
                .orElseThrow(() -> ApiException.notFound("별칭을 찾을 수 없습니다.")));
    }

    // ---------- 열 이름 프리셋 ----------

    public List<PresetResponse> presets(Long sellerId) {
        return presetRepository.findBySellerIdOrderByNameAsc(sellerId).stream().map(PresetResponse::of).toList();
    }

    /** 같은 이름이 있으면 덮어쓴다. */
    @Transactional
    public PresetResponse savePreset(Long sellerId, PresetRequest r) {
        ColumnMapping mapping = new ColumnMapping(blankToNull(r.dateColumn()), r.nameColumn().trim(),
                r.amountColumn().trim(), blankToNull(r.typeColumn()));
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
                .orElseThrow(() -> ApiException.notFound("저장된 열 설정을 찾을 수 없습니다.")));
    }

    // ---------- 내부 ----------

    private record ResolvedMapping(ColumnMapping mapping, String presetName) {
    }

    private ResolvedMapping resolveMapping(Long sellerId, ColumnMapping mapping, Long presetId) {
        if (presetId != null) {
            BankColumnPreset preset = presetRepository.findByIdAndSellerId(presetId, sellerId)
                    .orElseThrow(() -> ApiException.notFound("저장된 열 설정을 찾을 수 없습니다."));
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
        return new ParsedStatement(p.mapping(), p.autoDetected(), p.headerRowNo(), p.headers(), p.columnOptions(),
                p.preview(), p.deposits().subList(0, 300), p.skippedRows(), p.message());
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("파일을 선택해 주세요.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("파일을 읽을 수 없습니다.");
        }
    }

    static OrderBrief brief(CustomerOrder o) {
        if (o == null) {
            return null;
        }
        return new OrderBrief(o.getId(), o.getOrderNo(), o.getDepositorName(), o.getNickname(),
                PhoneNumbers.last4(o.getPhone()), o.getExpectedAmount(), o.getOrderedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
