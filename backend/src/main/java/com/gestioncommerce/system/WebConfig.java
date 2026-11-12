package com.gestioncommerce.system;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.gestioncommerce.auth.AuthInterceptor;
import com.gestioncommerce.license.ReadOnlyInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor auth;
    private final ReadOnlyInterceptor readOnly;

    public WebConfig(AuthInterceptor auth, ReadOnlyInterceptor readOnly) {
        this.auth = auth;
        this.readOnly = readOnly;
    }

    /** L'ordre compte : on identifie d'abord la personne, puis on applique la lecture seule de la licence. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/api/**");
        registry.addInterceptor(readOnly).addPathPatterns("/api/**");
    }
}
