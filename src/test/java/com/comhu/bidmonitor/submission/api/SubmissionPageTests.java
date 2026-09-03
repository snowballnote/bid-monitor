package com.comhu.bidmonitor.submission.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:submission-page;DB_CLOSE_DELAY=-1",
        "external-notice.scheduler.enabled=false"
})
class SubmissionPageTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesSubmissionPageAtShortAppShellRoutes() throws Exception {
        mockMvc.perform(get("/submissions"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/submissions/index.html"))
                .andExpect(content().string(""));

        mockMvc.perform(get("/submissions/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/submissions/index.html"));
    }
}
