package com.comhu.bidmonitor.externalnotice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:external-notice-page;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always"
})
class ExternalNoticePageTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesBizAssistHomeAndSharedAppShell() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Biz Assist")))
                .andExpect(content().string(containsString("href=\"/bids/\"")))
                .andExpect(content().string(containsString("href=\"/notices/\"")))
                .andExpect(content().string(containsString("href=\"/notifications/\"")))
                .andExpect(content().string(containsString("home.js")));

        mockMvc.perform(get("/common.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".app-sidebar")))
                .andExpect(content().string(containsString(".app-nav-link.active")));
    }

    @Test
    void servesExternalNoticePageWithSeparatedAssetsAndControls() throws Exception {
        mockMvc.perform(get("/notices/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/notices/index.html"));

        mockMvc.perform(get("/notices/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"collect-button\"")))
                .andExpect(content().string(containsString("id=\"pia-filter-button\"")))
                .andExpect(content().string(containsString("id=\"notice-modal\"")))
                .andExpect(content().string(containsString("notices.css")))
                .andExpect(content().string(containsString("/common.css")))
                .andExpect(content().string(containsString("notices.js")));

        mockMvc.perform(get("/notices/notices.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".badge-pia")));

        mockMvc.perform(get("/notices/notices.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/external-notices")))
                .andExpect(content().string(containsString("piaRelated=true")))
                .andExpect(content().string(containsString("method: \"POST\"")));
    }

    @Test
    void keepsExistingBidFeaturesAvailableAtDedicatedPath() throws Exception {
        mockMvc.perform(get("/bids/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/bids/index.html"));

        mockMvc.perform(get("/bids/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app.js")))
                .andExpect(content().string(containsString("style.css")))
                .andExpect(content().string(containsString("common.css")))
                .andExpect(content().string(containsString("id=\"range-search-form\"")))
                .andExpect(content().string(containsString("id=\"bid-list\"")));
    }

    @Test
    void servesNotificationSubscriberManagementPageAndAssets() throws Exception {
        mockMvc.perform(get("/notifications/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/notifications/index.html"));

        mockMvc.perform(get("/notifications/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"open-subscriber-modal\"")))
                .andExpect(content().string(containsString("id=\"subscriber-table-body\"")))
                .andExpect(content().string(containsString("id=\"subscriber-modal\"")))
                .andExpect(content().string(containsString("notifications.css")))
                .andExpect(content().string(containsString("notifications.js")));

        mockMvc.perform(get("/notifications/notifications.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".subscriber-card-list")));

        mockMvc.perform(get("/notifications/notifications.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/notification-subscribers")))
                .andExpect(content().string(containsString("method: \"POST\"")))
                .andExpect(content().string(containsString("method: \"PATCH\"")));
    }
}
