package com.comhu.bidmonitor.externalnotice.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 외부공지 화면의 짧고 안정적인 진입 주소를 정적 index 파일에 연결한다. */
@Controller
public class ExternalNoticePageController {

    @GetMapping({"/notices", "/notices/"})
    public String externalNoticePage() {
        return "forward:/notices/index.html";
    }
}
