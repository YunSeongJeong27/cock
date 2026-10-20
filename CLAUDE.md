# CLAUDE.md

입금콕 — 계좌이체로 돈을 받는 공구장을 위한 주문 정리 + 입금 대조 + 픽업 체크 서비스.

## 작업 전에 반드시
- `docs/design.md`를 먼저 읽는다. 설계·결정·미결 사항은 전부 거기에 있다.
- 같은 내용이 다르면 `docs/design.md` 맨 위 "읽는 순서와 우선순위"를 따른다.
- 결정이 바뀌면 `docs/design.md`를 고쳐서 커밋한다.
- 점검 기록(무엇을 확인·수정했는지)은 `docs/점검기록-YYYY-MM-DD.md`. 최근: `docs/점검기록-2026-10-20.md`

## 핵심 규칙 (요약, 자세한 건 docs/design.md)
- 사용자: 윤성, 자바 웹 개발자. 모르면 모른다, 애매하면 애매하다고 말한다. 추측을 사실처럼 말하지 않는다.
- 짧고 직접적으로. 사실과 판단을 구분해 표시한다. 사용자가 결정한 것은 다시 설득하지 않는다.
- "내 맥북"(사용자 PC)과 "Claude 클라우드 서버"(AI 작업 환경)를 구분해서 말한다.
- 사용자가 시키기 전에는 코드를 작성하지 않는다. 현재 단계·진행 상태는 docs/design.md "구현 결정"·"작업 흐름" 참고.
- 실행·테스트 방법은 README.md. 백엔드 `cd backend && ./mvnw test`, 프론트 `cd frontend && npm run typecheck`.
- `main`에 직접 push하지 않는다. 작업 브랜치 → PR → 사용자 머지.
- 비밀값(API 키 등)은 커밋하지 않는다.
- 문구: 서버 오류·입력값 검사는 `ErrorCode` (검사 어노테이션 message 에 enum 이름), 메일은 `MailText`, 화면 긴 문장은 `frontend/src/texts/` (docs/design.md "2026-10-20 문구 관리 방식").

## 기술 스택 (확정)
Java 21, Spring Boot 3.5.6, Maven(Wrapper), Spring Data JPA, Flyway, H2(개발)/PostgreSQL(운영), React(Vite+TS), 패키지 `com.depositcock`, 모노레포(`backend/`, `frontend/`).
