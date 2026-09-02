package com.comhu.bidmonitor.notification.subscriber.api;

import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.subscriber.service.NotificationSubscriberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-subscriber-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class NotificationSubscriberControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationSubscriberService service;

    @Test
    void registersAndListsSubscriber() throws Exception {
        mockMvc.perform(post("/api/notification-subscribers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "User@Example.com",
                                  "name": "홍길동",
                                  "notificationType": "PIA_EXTERNAL_NOTICE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.notificationType").value("PIA_EXTERNAL_NOTICE"))
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/notification-subscribers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("user@example.com"));
    }

    @Test
    void rejectsDuplicateEmailAndNotificationTypeCaseInsensitively() throws Exception {
        service.register("duplicate@example.com", "첫 번째", NotificationType.PIA_EXTERNAL_NOTICE);

        mockMvc.perform(post("/api/notification-subscribers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "DUPLICATE@example.com",
                                  "name": "두 번째",
                                  "notificationType": "PIA_EXTERNAL_NOTICE"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void disablesSubscriberAndExcludesItFromActiveLookup() throws Exception {
        Long id = service.register(
                "disabled@example.com",
                "비활성 대상",
                NotificationType.PIA_EXTERNAL_NOTICE
        ).getId();

        mockMvc.perform(patch("/api/notification-subscribers/{id}/disable", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertTrue(service.findEnabled(NotificationType.PIA_EXTERNAL_NOTICE).isEmpty());
    }
}
