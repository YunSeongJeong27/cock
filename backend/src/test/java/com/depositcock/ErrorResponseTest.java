package com.depositcock;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.depositcock.common.ErrorCode;
import com.depositcock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
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
 * 입력값 검사도 ErrorCode: 어노테이션 message 에 이름을 적고 처리기가 그 문구로 응답한다 (영어 기본 문구·필드 이름이 안 나가게).
 */
class ErrorResponseTest extends IntegrationTest {

    @Test
    void oneValidationErrorAnswersWithItsCode() throws Exception {
        mvc.perform(withJson(post("/api/auth/signup"), Map.of("email", uniqueEmail(), "password", "short", "displayName", "공구장")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_LENGTH"))
                .andExpect(jsonPath("$.detail").value(ErrorCode.PASSWORD_LENGTH.message()));
    }

    @Test
    void severalValidationErrorsAreJoinedWithoutFieldNames() throws Exception {
        mvc.perform(withJson(post("/api/auth/signup"), Map.of("email", uniqueEmail(), "password", "short", "displayName", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.detail").value(allOf(containsString(ErrorCode.PASSWORD_LENGTH.message()),
                        containsString(ErrorCode.SELLER_NAME_REQUIRED.message()), not(containsString("displayName")))));
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

    /** 검사 어노테이션의 message 가 빠졌거나, 없는 ErrorCode 이름이거나, 400 이 아닌 코드면 여기서 걸린다 */
    @Test
    void everyConstraintNamesAnErrorCode() throws Exception {
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
            ErrorCode code = ErrorCode.fromName(message);
            if (!code.name().equals(message) || code.status() != HttpStatus.BAD_REQUEST || code.message().contains("%")) {
                missing.add(where + " @" + a.annotationType().getSimpleName() + " " + message);
            }
        }
    }
}
