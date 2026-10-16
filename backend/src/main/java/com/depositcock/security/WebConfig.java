package com.depositcock.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SuspendedSellerInterceptor suspendedSellerInterceptor;

    public WebConfig(SuspendedSellerInterceptor suspendedSellerInterceptor) {
        this.suspendedSellerInterceptor = suspendedSellerInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(suspendedSellerInterceptor).addPathPatterns("/api/seller/**");
    }
}
