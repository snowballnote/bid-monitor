## 주요 기능

- 나라장터 OpenAPI를 통한 용역 입찰공고 조회
- 업종코드 6146(정보시스템 감리용역) 기준 필터링
- 소액수의 견적 / 적격심사제 공고 추출
- 추후 제안서 제출 여부 및 기술평가 여부 분석 기능 추가 예정

## 기술 스택

- Java 21
- Spring Boot
- Maven
- Thymeleaf
- Git / GitHub

## 실행 환경

나라장터 OpenAPI 인증키는 소스코드에 직접 저장하지 않고
Windows 환경변수 `G2B_SERVICE_KEY`를 통해 사용합니다.

`application.properties`

```properties
g2b.api.base-url=https://apis.data.go.kr/1230000/ad/BidPublicInfoService
g2b.api.service-key=${G2B_SERVICE_KEY}
현재 진행 상태
Spring Boot 프로젝트 생성
나라장터 OpenAPI 활용신청 및 호출 테스트 완료
API 인증키 환경변수 설정 완료
G2B API Service 기본 구조 작성 중