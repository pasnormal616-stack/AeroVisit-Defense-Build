package com.paf.aerovisit.config;

import com.paf.aerovisit.service.UploadMirror;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

// ☁️ Before /uploads/** or /exhibit-photos/** is served, a file that is missing on this server (wiped by a redeploy)
// is downloaded back from S3, so the existing resource handlers keep serving it from disk. Runs after Spring Security,
// so /uploads/** is still staff-only. Does nothing when the S3 mirror is off.
// ObjectProvider, not a hard dependency: @WebMvcTest slices load this class without the UploadMirror service.
@Configuration
public class UploadRestoreConfig implements WebMvcConfigurer {

    private final ObjectProvider<UploadMirror> mirror;
    private final Path exhibitsDir;

    public UploadRestoreConfig(ObjectProvider<UploadMirror> mirror,
                               @Value("${aerovisit.exhibits.dir:uploads/exhibits}") String exhibitsDir) {
        this.mirror = mirror;
        this.exhibitsDir = Paths.get(exhibitsDir);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                UploadMirror m = mirror.getIfAvailable();
                if (m != null && m.enabled()) {
                    m.restoreForRequest(request.getRequestURI().substring(request.getContextPath().length()), exhibitsDir);
                }
                return true;
            }
        }).addPathPatterns("/uploads/**", "/exhibit-photos/**");
    }
}
