package com.depositcock;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 오류 응답 모양 (2026-10-20): detail = 화면에 보여줄 한국어 문구, code = ErrorCode 이름.
 * 입력값 검사 문구는 DTO 어노테이션에 한국어로 직접 적는다 (영어 기본 문구·필드 이름이 화면에 나가지 않게).
 */
class ErrorResponseTest extends IntegrationTest {

    @Test
    void validationErrorsShowKoreanMessagesWithoutFieldNames() throws Exception {
        mvc.perform(withJson(post("/api/auth/signup"), Map.of("email", uniqueEmail(), "password", "short", "displayName", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.detail").value(allOf(containsString("비밀번호는 8~100자로 입력해 주세요."),
                        containsString("판매자 이름을 입력해 주세요."), not(containsString("displayName")))));
    }

    @Test
    void brokenJsonAndApiErrorsCarryCode() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        mvc.perform(withJson(post("/api/auth/login"), Map.of("email", uniqueEmail(), "password", "password123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    /** 새 DTO 에 검사 어노테이션을 달고 message 를 빼먹으면 여기서 걸린다 */
    @Test
    void everyConstraintHasKoreanMessage() throws Exception {
        List<String> missing = new ArrayList<>();
        int records = 0;
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:com/depositcock/**/*.class")) {
            String path = r.getURL().getPath();
            String name = path.substring(path.lastIndexOf("com/depositcock/")).replace(".class", "").replace('/', '.');
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            if (!type.isRecord()) {
                continue;
            }
            records++;
            for (Field field : type.getDeclaredFields()) {
                check(field.getAnnotations(), type.getSimpleName() + "." + field.getName(), missing);
                AnnotatedType annotated = field.getAnnotatedType();
                if (annotated instanceof AnnotatedParameterizedType p) {
                    for (AnnotatedType arg : p.getAnnotatedActualTypeArguments()) {
                        check(arg.getAnnotations(), type.getSimpleName() + "." + field.getName() + "<>", missing);
                    }
                }
            }
        }
        assertThat(records).as("검사한 record 수").isGreaterThan(30);
        assertThat(missing).isEmpty();
    }

    private static void check(Annotation[] annotations, String where, List<String> missing) throws Exception {
        for (Annotation a : annotations) {
            if (!a.annotationType().getPackageName().equals("jakarta.validation.constraints")) {
                continue;
            }
            String message = (String) a.annotationType().getMethod("message").invoke(a);
            if (message.startsWith("{")) {
                missing.add(where + " @" + a.annotationType().getSimpleName());
            }
        }
    }
}
