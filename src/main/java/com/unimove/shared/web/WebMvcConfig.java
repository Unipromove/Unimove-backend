package com.unimove.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final LastSeenInterceptor lastSeenInterceptor;

    public WebMvcConfig(LastSeenInterceptor lastSeenInterceptor) {
        this.lastSeenInterceptor = lastSeenInterceptor;
    }

    /** Painel de admin: forward (nao redirect) para nao gerar URL absoluta atras do proxy. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/painel").setViewName("forward:/painel/index.html");
        registry.addViewController("/painel/").setViewName("forward:/painel/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(lastSeenInterceptor)
                .excludePathPatterns("/auth/**", "/actuator/**");
    }
}
