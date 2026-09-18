# Biz Assist

사내 입찰·제안 업무에서 반복적으로 발생하는 공고 확인, 문서 분석, 제출서류 수집, 실적증빙 관리 업무를 줄이기 위해 만든 업무지원 도구입니다.

초기에는 나라장터 공고를 빠르게 조회하고 조건에 맞는 공고만 추리기 위한 도구로 시작했습니다.

이후 실제 업무에서 필요한 기능을 추가하면서 PIA 공지 수집, 이메일 알림, 제출서류 관리, 인력 자격서류 관리, 실적증빙 관리까지 범위를 확장했습니다.

프론트엔드는 초기 MVP 단계에서 HTML / CSS / Vanilla JavaScript로 구현했으며, 기능과 화면 상태가 복잡해지면서 React + Vite 기반으로 점진 전환하고 있습니다.

---

## 주요 기능

### 입찰공고 조회 및 분석

- 나라장터 OpenAPI를 통한 용역 입찰공고 조회
- 업종코드 기반 정보시스템 감리 관련 공고 필터링
- 소액수의 견적 / 적격심사제 등 검토 대상 공고 분류
- 동일 공고번호 기준 중복 공고 제거
- 외부사이트 확인이 필요한 공고 판정
- PDF / HWP / HWPX 첨부문서 분석
- 공고문에서 다음 정보 추출
  - 제출서류
  - 참가자격
  - 제출방법
  - 제출기한
- 문서 분석 결과와 원문 첨부파일 확인

### PIA 공지 및 이메일 알림

- 개인정보 영향평가 관련 외부공지 수집
- 신규 / 변경 공지 판정
- PIA 관련성 및 중요공지 분류
- 알림 이벤트 저장
- 중복 알림 방지
- SMTP 기반 이메일 발송
- 다중 알림 신청자 관리
- 발송 상태 관리
  - PENDING
  - SENT
  - FAILED

### 제출서류 관리

- 프로젝트별 제출서류 관리
- 카테고리별 필요서류 체크
- 사용자 정의 기타 서류 추가
- 준비된 서류 / 미준비 서류 상태 표시
- 프로젝트별 준비율 계산
- 제출 패키지 구성
- 회사 공통서류 관리
- 기존 프로젝트에 연결된 파일 snapshot 보존

### 회사 공통서류 관리

- 공통서류 master 관리
- 서류 추가
- 이름 수정
- 삭제
- 현재 파일 업로드 / 교체
- 기존 프로젝트에 이미 연결된 파일과 현재 master 파일 분리

### 인력·자격서류 관리

- 프로젝트별 투입인력 추가 / 제거
- 인력 이름 검색
- 사람별 필요서류 체크
- 지원 문서 유형
  - 프로필
  - 자격사본
  - KOSA 경력증명서
  - 개인정보 영향평가 전문인력 인증서
- 사람별 준비율 계산
- FMS 후보 파일 조회
- 후보 추천 표시
- 후보 선택 / 교체
- 파일 연결 해제
- PC 직접 업로드 / 교체
- 직접 업로드와 FMS reference 구분

### 기타 제출서류 관리

- 기타 필요서류 체크 / 해제
- 사용자 정의 서류 추가
- FMS 후보 조회
- 후보 선택 / 교체
- 프로젝트 연결 제거
- 준비율 반영

### 실적증빙 관리

- 실적 프로젝트 생성
- PPT 실적표 붙여넣기
- 실적별 사업명 / 수행기간 / 계약금액 / 발주처 저장
- 수행완료 / 수행중 상태 관리
- FMS Drive 기반 실적증명서 / 계약서 후보 검색
- 후보 추천 사유 표시
- 현재 연결 파일 표시
- 실적별 준비상태 관리
- KITC 요청 상태 관리
- 선택 파일 ZIP 다운로드
- 프로젝트 내 PPT 번호 중복 방지

현재 실적증빙 React 화면은 목록 / 상태 / 후보 조회까지 이전되어 있으며
후보 선택 / 직접 업로드 / 연결 해제 등은 점진 전환 중입니다.

---

## 기술 스택

### Frontend

- React
- Vite
- React Router
- HTML
- CSS
- Vanilla JavaScript
  - 기존 화면 유지
  - React로 점진 전환 중

### Backend

- Java 21
- Spring Boot
- Spring Web
- Spring JDBC
- Maven

### Database

- H2
  - Biz Assist 내부 application DB
- PostgreSQL
  - 회사 업무 DB read-only 연동

### Test

- JUnit
- Playwright

### External Integration

- 나라장터 OpenAPI
- FMS Drive API
- SMTP
- Company PostgreSQL

### Version Control

- Git
- GitHub

---

## 시스템 구조

```text
React / Vanilla JavaScript
        |
        v
Spring Boot REST API
        |
        +----------------------+
        |                      |
        v                      v
Biz Assist H2           Company PostgreSQL
                         Read Only
        |
        +----------------------+
        |                      |
        v                      v
나라장터 OpenAPI          FMS Drive API
                               |
                               v
                          Company NAS
````

Biz Assist 내부 상태는 H2에 저장합니다.

회사 PostgreSQL과 FMS / NAS는 기존 업무 데이터를 조회하기 위한 외부 시스템으로 사용하며
Biz Assist에서 회사 DB와 NAS 데이터를 직접 수정하지 않습니다.

---

## 데이터베이스 구조

### Biz Assist 내부 DB

Biz Assist 자체 데이터는 H2에 저장합니다.

```properties
spring.datasource.url=jdbc:h2:file:./data/bid-monitor
```

주요 저장 대상:

* 외부공지 수집 결과
* 알림 발송 상태
* 알림 신청자
* 제출 프로젝트
* 필요서류 선택 상태
* 공통서류 master
* 인력 / 자격서류 상태
* 실적 프로젝트
* 실적증빙 선택 상태
* FMS Drive 파일 인덱스

### 회사 업무 DB

회사 PostgreSQL은 조회 전용으로 연결합니다.

주요 조회 대상:

* 사업 정보
* 계약 정보
* RFP 관련 정보
* 기존 회사 업무 데이터

원칙:

```text
Biz Assist DB
→ H2
→ Biz Assist 자체 데이터 저장

Company DB
→ PostgreSQL
→ SELECT only
```

회사 DB 대상:

* INSERT 금지
* UPDATE 금지
* DELETE 금지
* DDL 금지

Biz Assist DB와 회사 DB는 별도 DataSource로 분리합니다.

---

## FMS Drive 연동

실적증빙과 인력 자격서류 후보를 찾기 위해
기존 FMS Drive API를 통해 회사 NAS 파일을 조회합니다.

Biz Assist가 NAS에 직접 접근하지 않고
기존 FMS Drive API를 통해서만 목록 / 권한 / 다운로드를 요청합니다.

### Drive 인덱스

매 후보 검색마다 NAS 전체를 탐색하지 않습니다.

```text
FMS / NAS
    |
    v
FMS Drive API
    |
    v
Drive Index Refresh
    |
    v
H2 drive_file_index
    |
    v
실적 / 인력 후보 검색
```

인덱스 갱신 시:

* 설정된 root와 하위 폴더 탐색
* 숨김파일 제외
* 임시파일 제외
* 성공한 root만 교체
* 실패 시 기존 index 보존
* 마지막 성공 상태 유지

후보 검색은 H2 index를 사용하며
실제 파일 선택 / 다운로드 시에는 다시 FMS에서 존재 여부와 권한을 확인합니다.

---

## FMS 인증

FMS 로그인 정보는 환경변수로 주입합니다.

```text
FMS_DRIVE_BASE_URL
FMS_DRIVE_LOGIN_ID
FMS_DRIVE_PASSWORD
FMS_DRIVE_COMPANY
```

최초 Drive 요청 시 FMS 로그인 API를 호출합니다.

성공 응답의 `SESSION` cookie를 서버 메모리에 보관하고
이후 Drive API 요청에 사용합니다.

401 응답이 발생하면:

```text
Drive 요청
→ 401
→ FMS 재로그인
→ 새로운 SESSION 저장
→ 기존 요청 1회 재시도
```

무한 재시도는 하지 않습니다.

수동 `FMS_DRIVE_SESSION_TOKEN` 방식은 사용하지 않습니다.

---

## 입찰 첨부문서 분석

지원 형식:

* PDF
* HWP
* HWPX

### PDF

Apache PDFBox를 사용하여 텍스트를 추출합니다.

### HWP

HWP parser를 사용하여 본문 텍스트를 추출합니다.

### HWPX

HWPX 파일을 ZIP 구조로 열고
`Contents/section*.xml`을 분석합니다.

추출한 텍스트에서 다음 내용을 규칙 기반으로 분석합니다.

* 참가자격
* 제출서류
* 제출방법
* 제출기한
* 제안서 관련 문구
* 외부사이트 확인 정보

---

## 첨부파일 보안 처리

외부 입찰 첨부파일 분석 과정에서 자원 고갈과 SSRF 위험을 줄이기 위해 검증을 적용합니다.

주요 검증:

* HTTP / HTTPS URL만 허용
* 허용된 G2B host 검증
* redirect 대상 재검증
* redirect 횟수 제한
* 다운로드 최대 크기 제한
* HWP signature 확인
* HWPX ZIP entry 개수 제한
* HWPX 압축 해제 총 크기 제한
* 문서 분석 timeout
* 제한된 thread pool 사용

후보 조회 GET API는 조회만 수행하도록 구성하고
reference 등록 / 갱신은 실제 사용자가 파일을 선택하는 write 요청에서만 수행합니다.

---

## 제출서류 수집 구조

제출 프로젝트는 다음 카테고리로 관리합니다.

```text
제출 프로젝트
|
+-- 회사 공통
|
+-- 인력 / 자격
|
+-- 기타 서류
|
+-- 실적증빙
```

각 영역은 독립적으로 파일을 검색 / 선택하며
프로젝트 상세에서는 전체 준비율을 함께 계산합니다.

---

## React 전환

초기 버전은 Spring Boot static resource 기반의
HTML / CSS / Vanilla JavaScript로 구현했습니다.

초기 목적은 나라장터 공고를 빠르게 조회하고
PIA 공지를 놓치지 않는 업무용 MVP를 만드는 것이었습니다.

기능이 증가하면서 다음 상태 관리가 복잡해졌습니다.

* 프로젝트별 준비율
* 여러 API 응답
* 파일 선택 상태
* FMS 후보
* 직접 업로드
* 모달
* 오류 복원
* 중복 요청 방지

이에 따라 기존 기능을 유지하면서
React + Vite로 점진 전환하고 있습니다.

현재 React 이전 완료 영역:

* Dashboard
* Submission 프로젝트 목록
* Submission 프로젝트 상세
* 회사 공통서류
* `/documents`
* 인력 / 자격
* 기타 서류
* 실적증빙 목록 / 상태 / 후보 조회

기존 Vanilla JavaScript 화면은 회귀 확인을 위해 아직 유지하고 있습니다.

---

## 실행 환경

### 나라장터 OpenAPI

```text
G2B_SERVICE_KEY
```

`application.properties`

```properties
g2b.api.base-url=https://apis.data.go.kr/1230000/ad/BidPublicInfoService
g2b.api.service-key=${G2B_SERVICE_KEY}
```

### 회사 PostgreSQL

```text
COMPANY_DB_URL
COMPANY_DB_USERNAME
COMPANY_DB_PASSWORD
COMPANY_DB_DRIVER
```

### 이메일 발송

```text
BIZ_ASSIST_MAIL_ENABLED
BIZ_ASSIST_MAIL_HOST
BIZ_ASSIST_MAIL_PORT
BIZ_ASSIST_MAIL_USERNAME
BIZ_ASSIST_MAIL_PASSWORD
BIZ_ASSIST_MAIL_FROM
```

### FMS Drive

```text
FMS_DRIVE_BASE_URL
FMS_DRIVE_LOGIN_ID
FMS_DRIVE_PASSWORD
FMS_DRIVE_COMPANY
FMS_DRIVE_INDEX_ROOTS
```

인증키와 비밀번호 등 민감한 값은 Git에 저장하지 않고
환경변수를 통해 주입합니다.

---

## 테스트

Backend:

* JUnit
* H2 memory database
* 외부 시스템 adapter 대역 테스트
* Company DB live 테스트 제외

Frontend:

* Playwright
* React 화면
* 기존 Vanilla JavaScript 최소 회귀
* 모바일 레이아웃
* 오류 / empty / loading 상태

주요 검증 항목:

* 후보 조회 GET의 read-only 보장
* 파일 선택 시에만 reference 등록
* 파일 업로드 실패 시 기존 연결 상태 보존
* 프로젝트 준비율 계산
* 중복 요청 방지
* 동일 파일명 / 다른 경로 파일 식별
* 외부 문서 분석 보안 제한

---

## 현재 진행 상태

### 완료

* Spring Boot 기본 구성
* 나라장터 OpenAPI 연동
* 서비스키 환경변수 처리
* 입찰공고 조회 / 필터링
* 업종코드 기반 감리 공고 조회
* 중복 공고 제거
* PDF / HWP / HWPX 분석
* 제출서류 / 참가자격 / 제출방법 / 제출기한 추출
* 외부사이트 확인
* PIA 외부공지 수집
* PIA 중요공지 분류
* SMTP 이메일 알림
* 다중 알림 신청자 관리
* H2 / 회사 PostgreSQL DataSource 분리
* Company DB read-only 연동
* 제출 프로젝트
* 회사 공통서류 관리
* 인력 / 자격서류 관리
* 기타 제출서류 관리
* 실적 프로젝트
* FMS Drive index
* FMS 자동 SESSION 인증
* 실적증빙 ZIP
* React 기반 전환
* React Dashboard
* React Submission 목록 / 상세
* React 회사 공통서류
* React 인력 / 자격
* React 기타 서류
* React 실적증빙 목록 / 후보 조회

### 개발 중

* React 실적증빙 파일 관리

  * 후보 선택 / 교체
  * 직접 업로드 / 교체
  * 연결 해제
  * 실적 추가 / 수정

### 향후 계획

* React 전환 완료 후 기존 Vanilla JavaScript 정리
* 외부 조달사이트 공고 수집 확대
* 나라장터 사업명 기반 보완 검색
* PostgreSQL 전환
* Docker / Docker Compose
* GitHub Actions CI/CD
* 서버 배포