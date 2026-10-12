package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** ID photo uploads get an anonymous UUID file name that keeps the original extension; the visitor's name never appears. */
class AnonymousIdPhotoNameTest {

    private static String base(String fileName) {
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

    @Test
    void nameIsARandomUuidPlusTheOriginalExtension() {
        String jpg = VisitorPortalController.anonymousIdPhotoName("Juan_Dela_Cruz_ID.jpg", "image/jpeg");
        String jpeg = VisitorPortalController.anonymousIdPhotoName("scan.JPEG", "image/jpeg");
        String png = VisitorPortalController.anonymousIdPhotoName("Ana Santos - license.png", "image/png");

        assertThat(jpg).endsWith(".jpg");
        assertThat(jpeg).endsWith(".jpeg");
        assertThat(png).endsWith(".png");
        for (String name : new String[] {jpg, jpeg, png}) {
            assertThat(UUID.fromString(base(name)).toString()).isEqualTo(base(name)); // a real UUID, nothing else
            assertThat(name).doesNotContainIgnoringCase("juan").doesNotContainIgnoringCase("ana").doesNotContainIgnoringCase("license");
        }
    }

    @Test
    void everyUploadGetsADifferentName() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 500; i++) names.add(VisitorPortalController.anonymousIdPhotoName("id.jpg", "image/jpeg"));
        assertThat(names).hasSize(500);
    }

    @Test
    void anUnknownOrHostileExtensionFallsBackToTheCheckedContentType() {
        assertThat(VisitorPortalController.anonymousIdPhotoName("id.php", "image/jpeg")).endsWith(".jpg").doesNotContain("php");
        assertThat(VisitorPortalController.anonymousIdPhotoName("x.png/../../evil", "image/png")).matches("[0-9a-f-]{36}\\.png");
        assertThat(VisitorPortalController.anonymousIdPhotoName("noextension", "image/png")).endsWith(".png");
        assertThat(VisitorPortalController.anonymousIdPhotoName(null, "image/jpeg")).matches("[0-9a-f-]{36}\\.jpg");
    }
}
