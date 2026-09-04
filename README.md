## 주요 기능

- 나라장터 OpenAPI를 통한 용역 입찰공고 조회
- 업종코드 6146(정보시스템 감리용역) 기준 필터링
- 소액수의 견적 / 적격심사제 공고 추출
- 외부 공고 및 개인정보 영향평가(PIA) 관련 공지 수집
- PIA 중요공지 이메일 알림
- 알림 신청자 등록 및 관리
- 입찰 첨부문서(PDF/HWP/HWPX) 분석
- 제출서류, 참가자격, 제출방법, 마감기한 등 문서 분석
- 제출서류 수집 기능 개발 중
  - 필요서류 체크 및 사용자 정의 서류 입력
  - 회사 DB 기반 문서 후보 검색
  - 사용자 선택 및 제출 패키지 구성

## 기술 스택

- Java 21
- Spring Boot
- Maven
- Spring JDBC
- H2
- PostgreSQL
- HTML / CSS / Vanilla JavaScript
- Git / GitHub

## 데이터베이스 구조

### Biz Assist 내부 DB

H2를 사용하여 Biz Assist 자체 데이터를 저장합니다.

주요 저장 대상:

- 외부공지 수집 결과
- 알림 발송 상태
- 알림 신청자
- 제출서류 수집 기능의 사용자 선택 상태

```properties
spring.datasource.url=jdbc:h2:file:./data/bid-monitor
```

### 회사 업무 DB

회사 PostgreSQL DB는 조회 전용으로 연결하여
사업, 계약, RFP, 문서 메타데이터 등을 조회합니다.

Biz Assist 내부 데이터와 회사 DB는 별도 DataSource로 분리합니다.

- Biz Assist DB: H2
- 회사 업무 DB: PostgreSQL
- 회사 DB 대상 INSERT / UPDATE / DELETE / DDL 금지
- 인증정보는 소스코드에 저장하지 않음

## 실행 환경

나라장터 OpenAPI 인증키는 소스코드에 직접 저장하지 않고
Windows 환경변수 `G2B_SERVICE_KEY`를 통해 사용합니다.

`application.properties`

```properties
g2b.api.base-url=https://apis.data.go.kr/1230000/ad/BidPublicInfoService
g2b.api.service-key=${G2B_SERVICE_KEY}
```

## 주요 환경변수

### 나라장터 OpenAPI

```text
G2B_SERVICE_KEY
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

인증키, 비밀번호 등 민감한 값은 Git에 저장하지 않고
환경변수를 통해 주입합니다.

## 현재 진행 상태

### 완료

- Spring Boot 프로젝트 기본 구성
- 나라장터 OpenAPI 연동
- 입찰공고 조회 및 필터링
- 입찰 첨부문서 PDF/HWP/HWPX 분석
- 공고 상세 및 확인사항 UI
- 외부공지 수집
- PIA 관련 공지 분류
- 이메일 알림 발송
- 다중 알림 신청자 관리
- 알림 관리 UI
- Biz Assist 공통 UI 디자인 정리
- 회사 PostgreSQL DEV read-only 연결 및 metadata 조회 검증
- H2 / 회사 PostgreSQL DataSource 분리

### 개발 중

- 제출서류 수집 기능
  - 카테고리별 필요서류 체크리스트
  - 회사 DB 기반 파일 후보 검색
  - 사용자 문서 선택
  - 제출 패키지 구성

### 추후 검토

- NAS 파일 연동
- 선택 문서 일괄 다운로드 / ZIP
- 문서 최신본 및 유효기간 관리
- 사내 서버 배포
- Docker 기반 운영 환경 구성
