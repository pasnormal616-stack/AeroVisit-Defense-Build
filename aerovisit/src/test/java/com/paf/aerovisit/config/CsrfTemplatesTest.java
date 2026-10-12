package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * CSRF is on, so every way a page can change data must carry the token. Reads the templates themselves, so a form or
 * fetch() added later without the token fails here instead of failing with a 403 in front of a user.
 */
class CsrfTemplatesTest {

    private static final Path TEMPLATES = Paths.get("src/main/resources/templates");
    private static final Pattern FORM_TAG = Pattern.compile("<form\\b[^>]*>", Pattern.DOTALL);
    private static final Pattern POST = Pattern.compile("method\\s*=\\s*\"post\"", Pattern.CASE_INSENSITIVE);
    private static final String HIDDEN_TOKEN = "th:name=\"${_csrf.parameterName}\" th:value=\"${_csrf.token}\"";

    private static List<Path> templates() throws IOException {
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            return files.filter(p -> p.toString().endsWith(".html")).toList();
        }
    }

    @Test
    void everyPostFormCarriesTheToken() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path file : templates()) {
            String html = Files.readString(file, StandardCharsets.UTF_8);
            Matcher form = FORM_TAG.matcher(html);
            while (form.find()) {
                String tag = form.group();
                if (!POST.matcher(tag).find()) continue;
                // th:action forms get the token from Thymeleaf; the booking form is guarded by its one-time KYC nonce
                if (tag.contains("th:action")) continue;
                if (tag.contains("id=\"mainRegForm\"")) {
                    assertThat(html.substring(form.end(), form.end() + 400)).contains("name=\"kycNonce\"");
                    continue;
                }
                String afterTag = html.substring(form.end(), Math.min(html.length(), form.end() + 300));
                if (!afterTag.contains(HIDDEN_TOKEN)) missing.add(file.getFileName() + ": " + tag.replaceAll("\\s+", " "));
            }
        }
        assertThat(missing).as("POST forms without a CSRF token").isEmpty();
    }

    // The whole fetch( ... ) call starting at 'start' (brackets counted, quoted text skipped)
    private static String fetchCall(String js, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start; i < js.length(); i++) {
            char c = js.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return js.substring(start, i + 1);
            }
        }
        return js.substring(start);
    }

    @Test
    void everyFetchThatChangesDataSendsTheTokenHeader() throws IOException {
        Pattern changesData = Pattern.compile("method:\\s*(?:['\"](?:POST|PUT|PATCH|DELETE)['\"]|method\\b)");
        List<String> missing = new ArrayList<>();
        int found = 0;
        for (Path file : templates()) {
            String html = Files.readString(file, StandardCharsets.UTF_8);
            for (int at = html.indexOf("fetch("); at >= 0; at = html.indexOf("fetch(", at + 1)) {
                String call = fetchCall(html, at + "fetch".length());
                if (!changesData.matcher(call).find()) continue; // a plain GET
                found++;
                if (!call.contains("AeroCsrf.headers(")) missing.add(file.getFileName() + ": fetch" + call.replaceAll("\\s+", " "));
            }
        }
        assertThat(found).as("fetch() calls that change data").isGreaterThanOrEqualTo(6);
        assertThat(missing).as("fetch() calls without the X-CSRF-TOKEN header").isEmpty();
    }

    @Test
    void bothPageHeadsPublishTheTokenForScripts() throws IOException {
        for (String fragment : new String[] {"fragments/shell.html", "fragments/visitor.html"}) {
            String html = Files.readString(TEMPLATES.resolve(fragment), StandardCharsets.UTF_8);
            assertThat(html).contains("<meta name=\"_csrf\" th:if=\"${_csrf != null}\" th:content=\"${_csrf.token}\">")
                .contains("<meta name=\"_csrf_header\" th:if=\"${_csrf != null}\" th:content=\"${_csrf.headerName}\">")
                .contains("<script src=\"/js/csrf.js\"></script>");
        }
    }

    @Test
    void noPageComparesUsernamesToDecideWhatToShow() throws IOException {
        // Screens use the isDirector / isGuard role flags from GlobalModelAdvice, so they never depend on an account's name
        for (Path file : templates()) {
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).as(file.toString())
                .doesNotContain("loggedInUser == 'director'")
                .doesNotContain("loggedInUser == 'guard'")
                .doesNotContain("loggedInUser === 'director'");
        }
    }
}
