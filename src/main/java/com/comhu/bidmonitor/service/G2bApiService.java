package com.comhu.bidmonitor.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

// 나라장터(G2B) OpenAPI 호출을 담당하는 서비스 클래스
@Service
public class G2bApiService {

    // application.properties에 설정한 나라장터 API 기본 주소를 가져옴
    @Value("${g2b.api.base-url}")
    private String baseUrl;

    // application.properties를 통해 Windows 환경변수의 API 인증키를 가져옴
    // 인증키를 소스코드에 직접 작성하지 않아 GitHub 등에 노출되는 것을 방지
    @Value("${g2b.api.service-key}")
    private String serviceKey;
}