package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.NewsPhoto;
import com.paf.aerovisit.model.NewsPost;
import com.paf.aerovisit.service.ExhibitPhotoStorage;
import com.paf.aerovisit.service.NewsPhotoStorage;
import com.paf.aerovisit.service.NewsService;
import com.paf.aerovisit.service.NewsService.NewsException;
import com.paf.aerovisit.service.NewsService.SaveAction;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 📰 Website Content > News, DIRECTOR ONLY: every path is under /admin/content/**, which SecurityConfig limits to the
// Director (a Guard gets 403). Posts are saved as form fields; photos are uploaded one per request by news-editor.js, so
// each request stays under the 15 MB multipart limit. Captions are plain text and are only ever shown escaped.
@Controller
public class NewsAdminController {

    private static final String LIST = "redirect:/admin/content#news";
    private static final String XHR = "X-Requested-With=XMLHttpRequest";

    private final NewsService newsService;

    public NewsAdminController(NewsService newsService) {
        this.newsService = newsService;
    }

    // ---------- Editor ----------

    @GetMapping("/admin/content/news/new")
    public String newPost(Model model) {
        addEditorLimits(model);
        model.addAttribute("post", null);
        model.addAttribute("today", newsService.today());
        return "news-editor";
    }

    @GetMapping("/admin/content/news/{id}/edit")
    public String editPost(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        NewsService.PostView post = newsService.find(id);
        if (post == null) {
            redirectAttributes.addFlashAttribute("error", "That post no longer exists.");
            return LIST;
        }
        addEditorLimits(model);
        model.addAttribute("post", post);
        model.addAttribute("today", newsService.today());
        return "news-editor";
    }

    private static void addEditorLimits(Model model) {
        model.addAttribute("maxPhotos", NewsPost.MAX_PHOTOS);
        model.addAttribute("maxCaption", NewsPost.MAX_CAPTION);
        model.addAttribute("maxAlt", NewsPhoto.MAX_ALT);
        model.addAttribute("maxPhotoMb", NewsPhotoStorage.MAX_BYTES / (1024 * 1024));
    }

    // ---------- Save (caption, date, photo order, alt texts, removed photos) ----------

    // The editor's fetch() call: JSON {ok, id, message}
    @PostMapping(value = "/admin/content/news/save", headers = XHR)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveJson(@RequestParam Map<String, String> form,
                                                        @RequestParam(value = "removePhoto", required = false) List<Long> removePhoto) {
        try {
            SaveAction action = action(form.get("action"));
            Long id = newsService.save(id(form.get("id")), form.get("caption"), form.get("postDate"), action,
                    ids(form.get("photoOrder")), alts(form), removePhoto);
            return ResponseEntity.ok(body(true, id, savedMessage(action)));
        } catch (NewsException e) {
            return ResponseEntity.badRequest().body(body(false, null, e.getMessage()));
        }
    }

    // The same form without JavaScript: back to the editor with a message
    @PostMapping("/admin/content/news/save")
    public String save(@RequestParam Map<String, String> form,
                       @RequestParam(value = "removePhoto", required = false) List<Long> removePhoto,
                       RedirectAttributes redirectAttributes) {
        Long id = id(form.get("id"));
        try {
            SaveAction action = action(form.get("action"));
            id = newsService.save(id, form.get("caption"), form.get("postDate"), action,
                    ids(form.get("photoOrder")), alts(form), removePhoto);
            redirectAttributes.addFlashAttribute("success", savedMessage(action));
        } catch (NewsException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            if (id == null) return "redirect:/admin/content/news/new";
        }
        return "redirect:/admin/content/news/" + id + "/edit";
    }

    // ---------- One photo per request ----------

    @PostMapping("/admin/content/news/{id}/photos")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addPhoto(@PathVariable Long id,
                                                        @RequestParam(value = "photo", required = false) MultipartFile photo,
                                                        @RequestParam(value = "alt", required = false) String alt) {
        try {
            NewsService.PhotoView added = newsService.addPhoto(id, photo, alt);
            Map<String, Object> body = body(true, id, "Photo added.");
            body.put("photo", Map.of("id", added.id(), "url", added.url(), "width", added.width(), "height", added.height()));
            return ResponseEntity.ok(body);
        } catch (NewsException | ExhibitPhotoStorage.InvalidImageException e) {
            return ResponseEntity.badRequest().body(body(false, id, e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(body(false, id, "The photo could not be saved on the server. Please try again."));
        }
    }

    // ---------- List actions (Website Content > News) ----------

    @PostMapping(value = "/admin/content/news/{id}/publish", headers = XHR)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> publishJson(@PathVariable Long id, @RequestParam("published") boolean published) {
        try {
            newsService.setPublished(id, published);
            return ResponseEntity.ok(body(true, id, published ? "Published." : "Moved back to drafts."));
        } catch (NewsException e) {
            return ResponseEntity.badRequest().body(body(false, id, e.getMessage()));
        }
    }

    @PostMapping("/admin/content/news/{id}/publish")
    public String publish(@PathVariable Long id, @RequestParam("published") boolean published, RedirectAttributes redirectAttributes) {
        try {
            newsService.setPublished(id, published);
            redirectAttributes.addFlashAttribute("success", published
                    ? "Post published. Visitors now see it in the News tab."
                    : "Post unpublished. It is a draft again and visitors no longer see it.");
        } catch (NewsException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return LIST;
    }

    @PostMapping("/admin/content/news/{id}/pin")
    public String pin(@PathVariable Long id, @RequestParam("pinned") boolean pinned, RedirectAttributes redirectAttributes) {
        try {
            newsService.setPinned(id, pinned);
            redirectAttributes.addFlashAttribute("success", pinned
                    ? "Post pinned. It stays at the top of the News feed."
                    : "Post unpinned.");
        } catch (NewsException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return LIST;
    }

    @PostMapping("/admin/content/news/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (newsService.delete(id)) {
            redirectAttributes.addFlashAttribute("success", "Post deleted, with its photos.");
        }
        return LIST;
    }

    // ---------- Form helpers ----------

    private static SaveAction action(String value) {
        if ("publish".equalsIgnoreCase(value)) return SaveAction.PUBLISH;
        if ("keep".equalsIgnoreCase(value)) return SaveAction.KEEP;
        return SaveAction.DRAFT;
    }

    private static String savedMessage(SaveAction action) {
        return switch (action) {
            case PUBLISH -> "Post saved and published.";
            case DRAFT -> "Post saved as a draft. Visitors do not see it until you publish it.";
            case KEEP -> "Post saved.";
        };
    }

    private static Long id(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new NewsException("That post no longer exists.");
        }
    }

    // "12,9,15" -> [12, 9, 15]; anything that is not a number is skipped
    static List<Long> ids(String csv) {
        List<Long> ids = new ArrayList<>();
        if (csv == null) return ids;
        for (String part : csv.split(",")) {
            try {
                if (!part.isBlank()) ids.add(Long.valueOf(part.trim()));
            } catch (NumberFormatException ignored) {
                // skipped
            }
        }
        return ids;
    }

    // alt_12=... -> {12: ...}
    static Map<Long, String> alts(Map<String, String> form) {
        Map<Long, String> alts = new LinkedHashMap<>();
        form.forEach((key, value) -> {
            if (key.startsWith("alt_")) {
                try {
                    alts.put(Long.valueOf(key.substring(4)), value);
                } catch (NumberFormatException ignored) {
                    // not a photo id
                }
            }
        });
        return alts;
    }

    private static Map<String, Object> body(boolean ok, Long id, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        if (id != null) body.put("id", id);
        body.put("message", message);
        return body;
    }
}
