package com.depositcock.vision;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.AppProperties;
import com.depositcock.seller.Seller;
import com.depositcock.seller.SellerRepository;
import com.depositcock.vision.CaptureUsageRepository.SellerImages;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캡처(비전 AI) 사용량 제한 (2026-10-13 사용자 결정): 판매자별 하루 장수 제한, 기본 30장.
 * 날짜는 Asia/Seoul 기준. AI 호출 전에 장수를 먼저 차감(예약)한다 — 호출이 실패해도 비용이 들 수 있어서 되돌리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CaptureUsageService {

    private final CaptureUsageRepository usageRepository;
    private final SellerRepository sellerRepository;
    private final AppProperties properties;
    private final Clock clock;

    public record Usage(int usedToday, int usedThisMonth, int dailyLimit, int remainingToday, boolean customLimit) {
    }

    /** 예약 결과. allowed=false 면 차감하지 않았다. */
    public record Reservation(boolean allowed, int remainingBefore, int dailyLimit) {
    }

    /** 설정 기본값. 0 이면 (판매자별 제한 0 과 같이) 모두 막는다. 음수·설정 없음이면 30. */
    public int defaultLimit() {
        return properties.vision() == null || properties.vision().dailyCaptureLimit() < 0
                ? 30 : properties.vision().dailyCaptureLimit();
    }

    public int effectiveLimit(Seller seller) {
        return seller.getDailyCaptureLimit() != null ? seller.getDailyCaptureLimit() : defaultLimit();
    }

    @Transactional(readOnly = true)
    public Usage usage(Long sellerId) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ApiException(ErrorCode.LOGIN_REQUIRED));
        LocalDate today = LocalDate.now(clock);
        int usedToday = usageRepository.findBySellerIdAndUsageDate(sellerId, today).map(CaptureUsage::getImageCount).orElse(0);
        int usedMonth = usageRepository.sumBetween(sellerId, today.withDayOfMonth(1), today);
        return usage(seller, usedToday, usedMonth);
    }

    /**
     * 여러 판매자의 사용량을 한 번에 읽는다 (관리자 판매자 목록). 전에는 판매자마다 오늘·이번 달을 따로 읽었음 (2026-10-21)
     *
     * @return 판매자 id → 사용량
     */
    @Transactional(readOnly = true)
    public Map<Long, Usage> usages(List<Seller> sellers) {
        LocalDate today = LocalDate.now(clock);
        Map<Long, Integer> usedToday = usageRepository.findByUsageDate(today).stream()
                .collect(Collectors.toMap(CaptureUsage::getSellerId, CaptureUsage::getImageCount));
        Map<Long, Integer> usedMonth = usageRepository.sumPerSellerBetween(today.withDayOfMonth(1), today).stream()
                .collect(Collectors.toMap(SellerImages::getSellerId, s -> s.getCount().intValue()));
        return sellers.stream().collect(Collectors.toMap(Seller::getId, s -> usage(s,
                usedToday.getOrDefault(s.getId(), 0), usedMonth.getOrDefault(s.getId(), 0)), (a, b) -> a));
    }

    private Usage usage(Seller seller, int usedToday, int usedMonth) {
        int limit = effectiveLimit(seller);
        return new Usage(usedToday, usedMonth, limit, Math.max(0, limit - usedToday), seller.getDailyCaptureLimit() != null);
    }

    /**
     * count 장을 쓸 수 있으면 차감하고 allowed=true. 남은 장수가 모자라면 아무것도 차감하지 않는다.
     * 별도 트랜잭션으로 바로 커밋한다 (이후 AI 호출이 실패해도 사용량은 남는다).
     * 판매자 행을 잠가서 같은 판매자의 동시 요청이 한도를 넘지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(Long sellerId, int count) {
        Seller seller = sellerRepository.findByIdForUpdate(sellerId).orElseThrow();
        LocalDate today = LocalDate.now(clock);
        CaptureUsage usage = usageRepository.findBySellerIdAndUsageDate(sellerId, today)
                .orElseGet(() -> usageRepository.save(new CaptureUsage(sellerId, today)));
        int limit = effectiveLimit(seller);
        int remaining = Math.max(0, limit - usage.getImageCount());
        if (count > remaining) {
            return new Reservation(false, remaining, limit);
        }
        usage.add(count);
        return new Reservation(true, remaining, limit);
    }
}
