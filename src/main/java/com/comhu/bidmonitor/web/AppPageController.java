package com.comhu.bidmonitor.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Biz Assist 정적 업무 화면에 짧고 일관된 진입 경로를 제공한다. */
@Controller
public class AppPageController {

    @GetMapping("/")
    public String homePage() {
        return "redirect:/react/index.html#/";
    }

    @GetMapping({"/bids", "/bids/"})
    public String bidPage() {
        return "forward:/bids/index.html";
    }

    @GetMapping({"/notifications", "/notifications/"})
    public String notificationSubscriberPage() {
        return "forward:/notifications/index.html";
    }

    @GetMapping({"/documents", "/documents/"})
    public String documentPage() { return "forward:/documents/index.html"; }

    @GetMapping({"/submissions", "/submissions/"})
    public String submissionPage() {
        return "forward:/submissions/index.html";
    }
}
