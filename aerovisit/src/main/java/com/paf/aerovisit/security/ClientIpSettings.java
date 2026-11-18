package com.paf.aerovisit.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 🌐 Hands the CloudFront origin secret (CLOUDFRONT_ORIGIN_SECRET) to ClientIp at startup. Blank = the
// CloudFront-Viewer-Address header is never trusted (local, ngrok, or before the CloudFront custom header is set up).
@Component
public class ClientIpSettings {

    @Value("${aerovisit.cloudfront.origin-secret:}")
    private String originSecret;

    @PostConstruct
    void apply() {
        ClientIp.configureOriginSecret(originSecret);
        if (originSecret == null || originSecret.isBlank()) {
            System.out.println("🌐 CloudFront-Viewer-Address is not trusted (CLOUDFRONT_ORIGIN_SECRET not set): per-IP limits use the connecting address.");
        }
    }
}
