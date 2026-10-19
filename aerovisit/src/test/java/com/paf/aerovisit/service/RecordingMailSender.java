package com.paf.aerovisit.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * A JavaMailSender that never opens a connection: each MimeMessage EmailService builds is recorded (or the configured
 * failure is thrown, standing in for Gmail refusing the login or the network dropping).
 */
class RecordingMailSender extends JavaMailSenderImpl {

    /** What one sent email looked like: addresses, subject, HTML / plain-text bodies, attachments by file name and
     *  inline images by Content-ID (the "cid:" an <img> in the HTML points to). */
    record SentEmail(String to, String fromAddress, String fromName, String subject, String html, String plainText,
                     Map<String, byte[]> attachments, Map<String, byte[]> inlineImages) {}

    final List<SentEmail> sent = new ArrayList<>();
    MailException failure;

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
        if (failure != null) throw failure;
        for (MimeMessage message : mimeMessages) sent.add(read(message));
    }

    private static SentEmail read(MimeMessage message) {
        try {
            message.saveChanges(); // fills in the Content-Type headers, as a real send would
            InternetAddress from = (InternetAddress) message.getFrom()[0];
            String[] bodies = new String[2]; // [html, plain text]
            Map<String, byte[]> attachments = new LinkedHashMap<>();
            Map<String, byte[]> inlineImages = new LinkedHashMap<>();
            walk(message, bodies, attachments, inlineImages);
            return new SentEmail(message.getRecipients(Message.RecipientType.TO)[0].toString(), from.getAddress(),
                    from.getPersonal(), message.getSubject(), bodies[0], bodies[1], attachments, inlineImages);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the email EmailService built", e);
        }
    }

    private static void walk(Part part, String[] bodies, Map<String, byte[]> attachments,
                             Map<String, byte[]> inlineImages) throws Exception {
        String[] contentId = part.getHeader("Content-ID");
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            attachments.put(part.getFileName(), bytes(part));
        } else if (Part.INLINE.equalsIgnoreCase(part.getDisposition()) && contentId != null && part.isMimeType("image/*")) {
            inlineImages.put(contentId[0].replaceAll("^<|>$", ""), bytes(part));
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) walk(multipart.getBodyPart(i), bodies, attachments, inlineImages);
        } else if (part.isMimeType("text/html")) {
            bodies[0] = (String) part.getContent();
        } else if (part.isMimeType("text/plain")) {
            bodies[1] = (String) part.getContent();
        }
    }

    private static byte[] bytes(Part part) throws Exception {
        try (InputStream in = part.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
