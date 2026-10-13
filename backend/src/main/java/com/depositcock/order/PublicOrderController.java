package com.depositcock.order;

import java.time.Duration;

import com.depositcock.common.AppProperties;
import com.depositcock.order.OrderDtos.CancelRequest;
import com.depositcock.order.OrderDtos.IdentifyRequest;
import com.depositcock.order.OrderDtos.PlaceOrderRequest;
import com.depositcock.order.OrderDtos.PlaceOrderResponse;
import com.depositcock.order.OrderDtos.PublicGroupBuy;
import com.depositcock.order.PublicOrderService.WithCookie;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객용 공개 API. 공구 링크 /g/{공구토큰} 화면이 호출한다.
 * 고객 기기는 쿠키 "dcg_{공구토큰}" 으로 기억한다 (공구 단위).
 */
@RestController
@RequestMapping("/api/public/g/{token}")
public class PublicOrderController {

    private static final Duration COOKIE_AGE = Duration.ofDays(365);

    private final PublicOrderService service;
    private final AppProperties properties;

    public PublicOrderController(PublicOrderService service, AppProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping
    public PublicGroupBuy view(@PathVariable String token, HttpServletRequest request) {
        return service.view(token, readCookie(request, token));
    }

    @PostMapping("/orders")
    public PlaceOrderResponse placeOrder(@PathVariable String token, @Valid @RequestBody PlaceOrderRequest body,
                                         HttpServletRequest request, HttpServletResponse response) {
        WithCookie<PlaceOrderResponse> result = service.placeOrder(token, readCookie(request, token), body);
        writeCookie(response, token, result.cookieToken());
        return result.body();
    }

    @PostMapping("/identify")
    public PublicGroupBuy identify(@PathVariable String token, @Valid @RequestBody IdentifyRequest body,
                                   HttpServletRequest request, HttpServletResponse response) {
        WithCookie<PublicGroupBuy> result = service.identify(token, readCookie(request, token), body);
        writeCookie(response, token, result.cookieToken());
        return result.body();
    }

    @PostMapping("/orders/{orderNo}/report-deposit")
    public PublicGroupBuy reportDeposit(@PathVariable String token, @PathVariable int orderNo, HttpServletRequest request) {
        return service.reportDeposit(token, readCookie(request, token), orderNo);
    }

    @PostMapping("/cancel")
    public PublicGroupBuy cancel(@PathVariable String token, @Valid @RequestBody CancelRequest body,
                                 HttpServletRequest request) {
        return service.cancel(token, readCookie(request, token), body);
    }

    static String cookieName(String token) {
        return "dcg_" + token;
    }

    private static String readCookie(HttpServletRequest request, String token) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName(token).equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeCookie(HttpServletResponse response, String token, String cookieToken) {
        if (cookieToken == null) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(cookieName(token), cookieToken)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(COOKIE_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
