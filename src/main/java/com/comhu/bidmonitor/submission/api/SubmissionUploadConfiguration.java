package com.comhu.bidmonitor.submission.api;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.MultipartConfigElement;
@Configuration
public class SubmissionUploadConfiguration {
    @Bean public MultipartConfigElement multipartConfigElement() { return new MultipartConfigElement("",20L*1024*1024,21L*1024*1024,0); }
}
