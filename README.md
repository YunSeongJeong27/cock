# 입금콕

> 계좌 입금내역만 올리면 입금자 콕! 공구 장부 한 번에

밴드·카카오톡 어디서 공구(공동구매)를 하든, **계좌이체로 돈을 받는 공구장**을 위한 웹 서비스입니다.
주문서 링크로 주문을 받고, 통장 거래내역을 올리면 입금자를 주문과 대조하고, 픽업(수령)까지 한 화면에서 관리합니다.

## 해결하려는 문제

계좌이체로 받는 공구장은 주문 댓글·채팅과 통장 입금내역을 하나씩 눈으로 맞춰 보며 입금을 확인합니다.
주문이 수십~수백 건이면 이 대조 작업에 많은 시간이 들고 실수도 생깁니다.

## 기능

| 구분 | 기능 |
|---|---|
| 주문서 링크 | 로그인 없는 고객 주문, 픽업/배송·결제 방식 선택, 재고 선착순(원자 처리), 쿠키로 재방문 인식, 입금자명+휴대폰 뒷자리로 본인 확인, "입금했어요" 신고, 주문 취소 |
| 입금 대조 | 은행 거래내역 엑셀(xlsx/xls)·CSV 업로드, 열 자동 인식 + 직접 지정 + 은행별 열 설정 저장, 자동 매칭/추천/후보 선택, 확정 시 입금자 별칭 저장(다음 공구 자동 매칭) |
| 스크린샷 | 은행앱 거래내역 캡처 → 비전 AI 추출 → 검수 → 대조 (AI 키 설정 시) |
| 댓글 집계 | 밴드 댓글 캡처·텍스트 → 해석·집계 → 주문으로 반영 |
| 정리 | 수령 체크(미입금 경고), 대시보드, 주문 정리표 엑셀, 입금 확인 문구 복사, 입금 확인된 닉네임 목록 |
| 관리자 | 판매자 목록, 무료 체험/유료 전환·만료일, 사용 제한 |

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 21, Spring Boot 3.5.6, Spring Data JPA, Flyway, Spring Security(JWT, httpOnly 쿠키), Apache POI, Maven Wrapper |
| Frontend | React 18, Vite, TypeScript, React Router |
| DB | H2 (개발, PostgreSQL 호환 모드), PostgreSQL (운영) |
| 비전 AI | `VisionExtractor` 인터페이스 (구현: Claude API Java SDK / 키 없으면 꺼짐) |

```
backend/   Spring Boot (패키지 com.depositcock)
frontend/  React (Vite + TS)
docs/      설계 문서 (docs/design.md)
```

## 로컬 실행 (내 맥북)

필요: JDK 21, Node.js LTS(22 권장). Maven 은 설치하지 않아도 됩니다 (`mvnw` 포함).

### 방법 1. 개발 모드 (화면 수정하면 바로 반영)

```bash
# 터미널 1: 백엔드 (http://localhost:8080)
cd backend
./mvnw spring-boot:run

# 터미널 2: 프론트 (http://localhost:5173, /api 는 8080 으로 전달)
cd frontend
npm install
npm run dev
```

브라우저에서 http://localhost:5173 을 엽니다.

### 방법 2. jar 하나로 실행 (배포와 같은 형태)

```bash
cd backend
./mvnw -Pfrontend package -DskipTests   # frontend 를 빌드해 jar 안에 넣는다 (npm 필요)
java -jar target/depositcock-0.0.1-SNAPSHOT.jar
```

브라우저에서 http://localhost:8080 을 엽니다.

### 로컬 계정·샘플 데이터

로컬 프로필(기본값)에서는 H2 파일 DB(`backend/data/`)를 쓰고, 처음 실행할 때 샘플 데이터가 들어갑니다.

| 용도 | 값 |
|---|---|
| 데모 판매자 | `demo@depositcock.local` / `demo1234` |
| 관리자 | `admin@depositcock.local` / `admin1234` |
| 고객 주문서 링크 | http://localhost:8080/g/demo-paprika (개발 모드는 5173) |
| H2 콘솔 | http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:file:./data/depositcock`, 사용자 `sa`) |

DB 를 처음 상태로 되돌리려면 앱을 끄고 `backend/data/` 폴더를 지우면 됩니다.

### 테스트

```bash
cd backend
./mvnw test          # H2 메모리 DB 로 단위·통합 테스트
cd ../frontend
npm run typecheck    # 타입 검사
```

## 운영 배포 (요약)

```bash
docker build -t depositcock .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:postgresql://호스트:5432/depositcock -e DB_USERNAME=... -e DB_PASSWORD=... \
  -e APP_JWT_SECRET=32바이트이상의임의문자열 \
  -e APP_ADMIN_EMAIL=... -e APP_ADMIN_PASSWORD=... \
  depositcock
```

| 환경변수 | 설명 |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL 접속 정보 |
| `APP_JWT_SECRET` | 로그인 토큰 서명 키 (32바이트 이상, 필수) |
| `APP_ADMIN_EMAIL`, `APP_ADMIN_PASSWORD` | 최초 관리자 계정 (이미 있으면 그대로 둠) |
| `APP_VISION_PROVIDER` | `anthropic` 이면 스크린샷 글자 읽기 켬 (기본 `fake` = 꺼짐) |
| `ANTHROPIC_API_KEY` | 위가 `anthropic` 일 때 필요 |
| `APP_VISION_MODEL` | 비전 모델 (기본 `claude-opus-5-5`) |

비밀값은 저장소에 올리지 않고 환경변수로만 넣습니다. 배포처는 아직 정하지 않았습니다 (docs/design.md 참고).

## 설계 문서

[`docs/design.md`](docs/design.md)
