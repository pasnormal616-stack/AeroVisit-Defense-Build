package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.AdminNotificationRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.UploadMirror;

/**
 * 🗑️ Security fix M6: Delete removes every file of the booking (old selfie, ID photo, legacy face crop, a Foreign
 * visitor's passport PDF, the QR), on disk and on S3, before the row; a booking with a receipt (proof of payment) is
 * refused; a file that can't be removed keeps the row. Real files in uploads/, mocked repositories.
 */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class DeleteFilesTest {

    private static final Path UPLOADS = Paths.get(System.getProperty("user.dir"), "uploads");

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;
    @MockitoBean private AdminNotificationRepository notificationRepo;
    @MockitoBean private UploadMirror uploadMirror;

    private final List<Path> made = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (Path p : made) Files.deleteIfExists(p);
    }

    private String file(String prefix, String ext) throws Exception {
        Files.createDirectories(UPLOADS);
        String name = prefix + "-m6test-" + UUID.randomUUID() + ext;
        Path p = UPLOADS.resolve(name);
        Files.write(p, new byte[] {1, 2, 3});
        made.add(p);
        return name;
    }

    // A booking with all five kinds of file, no payment
    private Visitor withAllFiles(long id) throws Exception {
        Visitor v = new Visitor();
        v.setId(id);
        v.setStatus("REJECTED");
        v.setIdImageUrl(file("selfie", ".jpg"));
        v.setIdCardImageUrl(file("idcard", ".jpg"));
        v.setIdFaceCropUrl(file("face", ".jpg"));
        v.setPassportFileUrl(file("passport", ".pdf"));
        v.setQrCodeUrl(file("QR", ".png"));
        when(repo.findById(id)).thenReturn(Optional.of(v));
        return v;
    }

    private static List<String> filesOf(Visitor v) {
        return List.of(v.getIdImageUrl(), v.getIdCardImageUrl(), v.getIdFaceCropUrl(), v.getPassportFileUrl(), v.getQrCodeUrl());
    }

    @Test
    void deleteRemovesEveryFileOnDiskAndOnS3IncludingThePassport() throws Exception {
        Visitor v = withAllFiles(71);
        when(uploadMirror.deleteUpload(anyString())).thenReturn(true);

        mvc.perform(post("/admin/delete/71").with(csrf()))
           .andExpect(redirectedUrl("/admin"))
           .andExpect(flash().attribute("success", "Visitor deleted and storage space freed up!"));

        for (String f : filesOf(v)) {
            assertThat(UPLOADS.resolve(f)).as(f).doesNotExist();
            verify(uploadMirror).deleteUpload(f);
        }
        verify(repo).delete(v);
    }

    @Test
    void aBookingWithAReceiptIsRefusedAndNothingIsRemoved() throws Exception {
        Visitor v = withAllFiles(72);
        v.setReceiptImageUrl("receipt-72.jpg");
        mvc.perform(post("/admin/delete/72").with(csrf()))
           .andExpect(flash().attribute("error", ManagementController.RECEIPT_ON_FILE_DELETE));
        for (String f : filesOf(v)) assertThat(UPLOADS.resolve(f)).as(f).exists();
        verify(uploadMirror, never()).deleteUpload(anyString());
        verify(repo, never()).delete(any(Visitor.class));
    }

    @Test
    void onlyEarlierReceiptsAreEnoughToRefuse() throws Exception {
        Visitor v = withAllFiles(73);
        v.setEarlierReceiptUrls("receipt-73-old.jpg");
        mvc.perform(post("/admin/delete/73").with(csrf()))
           .andExpect(flash().attribute("error", ManagementController.RECEIPT_ON_FILE_DELETE));
        verify(repo, never()).delete(any(Visitor.class));
    }

    @Test
    void aFileThatCannotBeRemovedFromS3KeepsTheRow() throws Exception {
        Visitor v = withAllFiles(74);
        when(uploadMirror.deleteUpload(anyString())).thenReturn(true);
        when(uploadMirror.deleteUpload(v.getPassportFileUrl())).thenReturn(false);

        mvc.perform(post("/admin/delete/74").with(csrf()))
           .andExpect(flash().attribute("error", "The booking was not deleted: some of its files could not be removed. Please try again."));
        verify(repo, never()).delete(any(Visitor.class));
        verify(notificationRepo, never()).deleteByVisitorId(74L);
    }
}
