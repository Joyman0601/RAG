package com.yhl.rag.demo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(DemoProperties.class)
public class DemoWebConfig implements WebMvcConfigurer {

    private final UploadEndpointGuard uploadEndpointGuard;

    public DemoWebConfig(UploadEndpointGuard uploadEndpointGuard) {
        this.uploadEndpointGuard = uploadEndpointGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(uploadEndpointGuard)
                .addPathPatterns("/api/documents/**", "/api/rag/documents/**", "/api/rag/documents");
    }
}
