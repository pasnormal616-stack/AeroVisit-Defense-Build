package com.paf.aerovisit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // "/home" has no page of its own, so send it to the visitor portal instead of a 404.
        registry.addRedirectViewController("/home", "/");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // This tells Spring Boot: "If the website asks for an image in /uploads/, allow it to be viewed."
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }
}