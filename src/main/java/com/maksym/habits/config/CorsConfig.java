package com.maksym.habits.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // Allow your production frontend + local dev
                .allowedOrigins(
                        "https://melodious-tenderness-production.up.railway.app",
                        "http://localhost:5173"
                )
                // If you want to allow any *.up.railway.app, use allowedOriginPatterns instead:
                // .allowedOriginPatterns("https://*.up.railway.app", "http://localhost:5173")
                .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
