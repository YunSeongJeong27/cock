package com.depositcock.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * React 라우터 경로(/g/abc, /seller/... 등)를 새로고침해도 index.html 을 돌려준다.
 * /api 로 시작하는 경로와 점(.)이 들어간 파일 경로는 여기 걸리지 않는다.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/{path:^(?!api|h2-console)[^\\.]*}", "/{path:^(?!api|h2-console)[^\\.]*}/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
