package com.paf.aerovisit.controller;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Test helper for POST /save: puts the same one-time KYC nonce in the session and in the form, as the booking page does
// (VisitorPortalController.showForm), so a test reaches the booking checks behind the nonce check.
public final class KycNonceSupport {

    private KycNonceSupport() {}

    public static RequestPostProcessor kycNonce() {
        return request -> {
            String nonce = java.util.UUID.randomUUID().toString();
            request.getSession(true).setAttribute(VisitorPortalController.KYC_NONCE, nonce);
            request.addParameter(VisitorPortalController.KYC_NONCE, nonce);
            return request;
        };
    }
}
