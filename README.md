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

## 실적증빙 일괄 수집 MVP

- 화면: `/performances/index.html` (서류 모으기 → 실적증빙 일괄 수집)
- 프로젝트명·마감일을 등록하고, 목록에서 프로젝트명 / D-day / 상태만 확인합니다.
- PPT의 번호 / 사업명 / 사업기간 / 계약금액 / 발주처 표 전체를 붙여넣습니다.
  HTML 표를 우선 사용하며, 텍스트에서는 탭 구분과 따옴표로 감싼 셀 내부 줄바꿈을 지원합니다.
  PPT 번호와 계약금액 원문을 유지하고, 정상 행은 저장하며 오류 행은 화면에서 수정·재시도합니다.
  병합 셀이나 경계가 모호한 텍스트는 오류 행을 확인해 5개 셀로 수정하세요.
- 사업기간은 `2024.01 ~ 2025.12`, `2024-01-01 ~ 2025-12-31`, `2024.01 ~ 수행중` 형식입니다.
  종료일 당일까지 수행중으로 판정하며, 월 단위 종료일은 해당 월 말일입니다.
  저장된 실적은 사업 상태를 직접 지정할 수도 있습니다.
- 수행완료 사업은 FMS Drive의 실적증명서·실적증명원 후보를 먼저 조회하고, 없을 때 계약서를 조회합니다.
  수행중 사업은 계약서만 조회하며, 정상 조회 결과 후보가 없으면 KITC 요청 필요를 표시합니다.
  H2 Drive 인덱스의 파일명에서 사업명·발주처를 정규화하고 키워드 일치율로 추천합니다.
  발주처만 일치하는 파일은 제외하며, 사업명이 충분히 일치하면 발주처 확인이 필요한 후보로 추천합니다.
  기존 제출서류 OR 검색은 유지합니다. 추천만으로 파일을 선택하지 않습니다.
- 실적마다 정보·선택파일·증빙유형·KITC 상태를 저장합니다.
  요청함에는 사용자가 입력한 요청일, 회신받음에는 요청일과 회신일이 필요합니다.
  회신일은 요청일 이후여야 합니다. 파일 선택과 KITC 이력은 별개이며 날짜를 자동 입력하지 않습니다.
- 파일 ID로 직접 연결하거나 연결을 해제할 수 있고, 같은 파일을 여러 실적에 연결할 수 있습니다.
  저장 버튼으로 반영한 중간 상태는 H2에 유지됩니다. 프로젝트 내 같은 PPT 번호는 중복 저장하지 않습니다.
- ZIP에는 선택된 실적마다 `PPT번호_증빙유형_(발주처) 사업명.ext`로 파일을 수록합니다.
  경로 구분자 등 안전하지 않은 파일명 문자는 치환하며, 치환 후 이름이 겹치면 오류를 반환합니다.
  원본 합계 100MB까지 지원하며, 읽지 못하는 파일이 있으면 부분 ZIP 대신 오류를 반환합니다.

### FMS Drive 검색·다운로드 설정

- `FMS_DRIVE_BASE_URL`: FMS 서버 기본 URL
- `FMS_DRIVE_SESSION_TOKEN`: FMS 인증 세션 값. 서버에서 `SESSION` 쿠키로 전달하며 브라우저에 공개하지 않습니다.
- `FMS_DRIVE_COMPANY`: 기본값 `CNH`
- `FMS_DRIVE_CERTIFICATE_FOLDERS`, `FMS_DRIVE_CONTRACT_FOLDERS`: 각 검색 폴더의 절대 Drive 경로, 여러 폴더는 쉼표로 구분합니다.

화면의 **Drive 인덱스 갱신** 버튼으로 최초 인덱스를 구축합니다.
버튼은 `POST /api/drive-index/refresh`를 호출하고, `GET /api/drive-index`는 경로 없는 범위별 상태를 반환합니다.
갱신 작업만 `GET /api/drive/list?path=...&company=...`로 설정 폴더와 하위 폴더를 탐색합니다.
숨김파일·폴더와 `~$` 임시파일을 제외하며, 기존 검색 깊이·폴더 수·파일 수 제한을 유지합니다.
루트 전체 탐색 성공 후에만 H2 `drive_file_index`를 트랜잭션으로 교체합니다.
실패하면 이전 인덱스와 마지막 성공 시각·파일 수를 보존하고 `drive_index_root_state`에 실패를 기록합니다.
미구축·갱신 중·실패한 범위는 후보 0건으로 취급하지 않고 검색 오류를 반환합니다.

import 후 자동 후보 검색은 H2 인덱스만 조회합니다. 매 행마다 FMS를 탐색하지 않습니다.
원본 파일이 추가·이동·삭제되면 수동으로 갱신하고 후보 추천을 다시 실행하세요.
스케줄러·증분 동기화는 없으며, 선택 시 실제 파일 존재 확인과 다운로드 권한 확인은 계속 FMS에서 수행합니다.
기존 `performance_drive_file` 및 선택 ID는 그대로 유지됩니다.
중복된 설정 루트는 한 번 갱신하고, 중첩 루트의 같은 파일은 후보에서 중복 제거합니다.
확인한 FMS 소스에서는 목록 DTO의 `canDownload`가 설정되지 않아 기본값 false가 될 수 있습니다.
ZIP은 `GET /api/drive/permission?path=...`의 실제 다운로드 권한을 확인한 후
`GET /api/drive/download?path=...`로 파일을 받아 PPT 번호별 이름으로 묶습니다.
권한이 없거나 파일을 받지 못하면 오류를 반환하며 NAS로 우회하지 않습니다.
실제 배포 서버의 세션·폴더 권한과 다운로드 성공 여부는 운영 연결 후 확인해야 합니다.

Drive 경로는 애플리케이션 H2의 내부 파일 참조로 저장하고 API에는 불투명한 `driveFileId`만 전달합니다.
후보/실적/API 오류 응답에는 Drive 경로·storage_path·credential을 포함하지 않습니다.
기존 회사 파일 ID의 직접 연결은 유지하며, 해당 파일의 ZIP에는 기존 `PERFORMANCE_NAS_ROOT` 설정을 사용합니다.
기존 `CompanyFileSearchPort` / `companyJdbcTemplate` 일반 제출서류 검색은 유지합니다.
회사 DB/NAS 생성·수정·삭제, 자동 최종선택, KITC·메일 연동, OCR, 권한 변경, JPA·PostgreSQL 전환은 포함하지 않습니다.

### 변경 영역 테스트

```powershell
./mvnw.cmd "-Dtest=DriveEvidenceServiceTests,FmsDriveHttpAdapterTests,PerformanceWorkflowTests,PerformanceControllerTests,NasPerformanceFileContentAdapterTests,JdbcCompanyFileSearchAdapterTests,SubmissionPersistenceTests,CommonSubmissionDocumentControllerTests,CommonSubmissionDocumentServiceTests,CompanyReadOnlyDataSourceConfigurationTests" "-Dcompany-db.enabled=false" "-Dexternal-notice.scheduler.enabled=false" test
node --check src/main/resources/static/performances/performances.js
git diff --check
```

테스트는 메모리 H2와 임시 파일/대역을 사용합니다. 회사 PostgreSQL live 테스트는 실행하지 않습니다.
실제 PPT 클립보드와 회사 NAS 경로 매핑은 운영 환경에서 최종 확인이 필요합니다.
