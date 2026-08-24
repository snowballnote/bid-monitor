package com.comhu.bidmonitor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;

// 나라장터(G2B) OpenAPI 호출을 담당하는 서비스 클래스
@Service
public class G2bApiService {

    // application.properties에 설정한 나라장터 API 기본 주소를 가져옴
    @Value("${g2b.api.base-url}")
    private String baseUrl;

    // Windows 환경변수에 저장된 나라장터 API 인증키를 가져옴
    // 실제 인증키가 GitHub에 노출되지 않도록 소스코드에는 직접 작성하지 않음
    @Value("${g2b.api.service-key}")
    private String serviceKey;

    /**
     * 나라장터 용역 입찰공고 목록을 테스트용으로 조회한다.
     */
    public String getBidList() {

        // 브라우저에서 정상 호출된 URL과 동일한 형태로 전체 요청 주소를 직접 만든다.
        // ServiceKey가 이미 URL Encoding된 값이므로 RestClient가 다시 인코딩하지 않도록
        // 최종 문자열을 URI 객체로 변환해 그대로 전달한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoServc"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=1"
                + "&inqryBgnDt=202608240000"
                + "&inqryEndDt=202608242359";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }
}