package com.gestioncommerce.license;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LicenseWebConfig implements WebMvcConfigurer {

    private final ReadOnlyInterceptor readOnly;

    public LicenseWebConfig(ReadOnlyInterceptor readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(readOnly).addPathPatterns("/api/**");
    }
}
