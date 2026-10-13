package com.depositcock.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * React 화면 경로(/g/abc, /seller/... 등)를 새로고침해도 index.html 을 돌려준다.
 * 정적 파일(/assets/*.js 등)과 /api 는 여기 걸리지 않도록 화면 경로만 나열한다.
 * 프론트에 새 최상위 경로를 추가하면 여기에도 추가해야 한다.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/signup", "/privacy", "/terms", "/admin", "/seller", "/seller/**", "/g/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
