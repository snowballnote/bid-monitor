# Biz Assist React 전환 1단계

기존 대시보드를 React + Vite로 병행 제공한다. Spring Boot API/DB/FMS 및 기존 static 화면은 변경하지 않는다.

## 개발

Node.js 22.12 이상에서 `frontend/` 기준:

```sh
npm ci
npm run dev
```

Spring Boot를 기존 방식으로 실행하고 `http://127.0.0.1:5173/react/index.html#/`에 접속한다.
Vite가 `/api`와 기존 화면 및 공통 정적 자원 요청을 `http://localhost:8080`으로 전달한다.
다른 백엔드 주소는 `.env.local`의 `SPRING_BOOT_URL=http://localhost:8080`으로 지정한다.
환경변수는 개발/preview 프록시 설정에만 사용하며 브라우저 번들에 포함하지 않는다.

## 빌드 / Spring Boot 제공 경로

```sh
npm run build:spring
```

`dist/`의 결과를 `../src/main/resources/static/react/`에 복사한다.
이후 기존 Spring Boot 실행 또는 Maven 패키징을 수행하면 `/react/index.html#/`에서 접근한다.
개발 중 백엔드만 실행할 때도 먼저 이 명령을 실행하면 정적 빌드를 제공할 수 있다.
CI에서는 `npm ci` → `npm run build:spring` → 기존 Maven 패키징 순서로 실행한다.
`dist/`와 복사한 bundle은 Git에서 제외한다. Maven clean/package 자체는 프론트를 빌드하지 않는다.
복사는 React 전용 경로에만 수행하며 기존 `/index.html`, `/submissions/`, `/documents/`, `/performances/` 등을 덮어쓰지 않는다.

## 구조와 라우팅

- `src/pages/Dashboard.jsx`: 업무 요약, 최근 작업, 빠른 작업, 중요공지
- `src/components/`: AppLayout, Button, StatusBadge
- `src/api/`: 기존 GET API 호출과 응답 배열 검증, 최대 4개 동시 증빙 조회
- `src/main.jsx`: React Router HashRouter. 대시보드는 `#/`, 목록은 `#/submissions`, 상세는 `#/submissions/:caseId`, 알 수 없는 hash는 홈으로 이동
- 서류 모으기 메뉴와 대시보드의 목록 링크는 React 목록으로 이동한다. 나머지 메뉴와 대시보드 최근 작업 링크는 기존 화면을 유지한다
- 기존 `common.css`와 `home.css`를 직접 import하여 개발·빌드에서 같은 스타일 사용
- `scripts/stage-spring.mjs`: Spring Boot 정적 경로로 빌드 복사

HashRouter는 서버의 SPA fallback이나 컨트롤러 수정 없이 새로고침과 직접 접근을 지원한다.
프로젝트 목록은 `pages/submissions`, `components/submissions`, `api/submissions`로 분리했다.
기존 `/api/submission-cases` GET/POST 및 `/{id}` PUT/DELETE를 사용하고 보기 방식은 기존
`biz-assist.submissions.view` 저장 키를 공유한다. 생성 성공 및 카드/행 클릭은
`#/submissions/{id}` React 상세 화면으로 이동한다.
기존 vanilla 목록과 상세 파일은 유지한다.

상세는 `/{id}`, `/{id}/requirements`, `/{id}/package`, `/{id}/people`,
`/api/submission-document-masters`와 이미 연결된 실적의 `/api/performance-projects/{id}/entries`를 GET으로 조회한다.
기존 저장 응답으로 전체/카테고리 준비율과 선택된 서류·파일을 표시한다. 필수 조회가 실패하면
불완전한 준비 수치 대신 오류와 새로고침을 제공한다. ZIP은 기존 `/{id}/download`를 사용한다.
회사 공통서류 체크는 `/{id}/collect?preserveExistingSelections=true`에 전체 요구서류 목록을 POST한다.
다른 카테고리의 요구서류는 그대로 보존하며, 새 체크에는 현재 마스터 파일을 연결하고 기존 프로젝트 연결 파일은 유지한다.
연결된 서류 해제 시 확인하며, 저장은 직렬화하고 requirements/package 재조회로 체크·파일·준비율을 갱신한다.
저장 실패 시 서버 상태를 재조회한다. 재조회까지 실패하면 체크를 잠그고 새로고침을 안내한다.
공통서류 파일 업로드·교체는 기존 `/documents/`에 둔다. 인력 관리·실적 연결·기타 파일 관리는 기존 상세 화면으로 연결한다.
회사 공통서류 관련 검증은 `npx playwright test common-documents.spec.js submission-detail.spec.js`로 실행한다.

목록 관련 테스트만 실행하려면 `npm run build` 후
`npx playwright test submissions.spec.js submission-detail.spec.js`를 실행한다.

## 관련 프론트 검증

```sh
npm test
```

production bundle을 preview에서 실행하고 기존 대시보드 회귀 사례를 Playwright로 검증한다.
API 응답은 브라우저에서 mock하므로 DB/API 서버를 실행하거나 데이터를 변경하지 않는다.
Windows의 Microsoft Edge를 사용한다. 다른 환경에서는 Playwright browser/channel 설정을 조정한다.

설정 참고: [Vite](https://vite.dev/guide/), [React Router](https://reactrouter.com/start/declarative/installation).
