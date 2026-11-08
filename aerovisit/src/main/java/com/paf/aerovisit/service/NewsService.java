package com.paf.aerovisit.service;

import com.paf.aerovisit.config.ClockConfig;
import com.paf.aerovisit.model.NewsPhoto;
import com.paf.aerovisit.model.NewsPost;
import com.paf.aerovisit.repository.NewsPhotoRepository;
import com.paf.aerovisit.repository.NewsPostRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 📰 The News feed (Website Content > News): the visitor feed pages and the Director's edits.
// Views get plain records (PostView / PhotoView), never JPA objects, so nothing lazy is touched after the transaction
// (Open Session In View is off). Every message in a NewsException is safe to show to the Director as-is.
@Service
public class NewsService {

    static final int MAX_PAGE = 10_000;
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private final NewsPostRepository postRepo;
    private final NewsPhotoRepository photoRepo;
    private final NewsPhotoStorage photoStorage;

    // ⏱️ "Today" for a post without a date (the shared clock; optional so hand-built services use the system clock)
    @Autowired(required = false)
    Clock clock;

    public NewsService(NewsPostRepository postRepo, NewsPhotoRepository photoRepo, NewsPhotoStorage photoStorage) {
        this.postRepo = postRepo;
        this.photoRepo = photoRepo;
        this.photoStorage = photoStorage;
    }

    // ---------- What the pages show ----------

    public record PhotoView(Long id, String filename, String altText, String alt, int width, int height) {
        public String url() { return "/exhibit-photos/" + filename; }
    }

    public record PostView(Long id, String caption, LocalDate postDate, boolean published, boolean pinned, List<PhotoView> photos) {
        public String dateLabel() { return postDate == null ? "" : DATE_LABEL.format(postDate); }
        public String isoDate() { return postDate == null ? "" : postDate.toString(); }
        public int photoCount() { return photos.size(); }
        public boolean hasCaption() { return caption != null && !caption.isBlank(); }
        public PhotoView cover() { return photos.isEmpty() ? null : photos.get(0); }
        // The admin list's one-line summary
        public String firstLine() {
            if (!hasCaption()) return "(no caption)";
            String line = caption.strip().split("\n", 2)[0];
            return line.length() > 80 ? line.substring(0, 80) + "…" : line;
        }
    }

    // One page of the visitor feed; nextPage is null on the last page
    public record FeedPage(List<PostView> posts, Integer nextPage) {}

    // Published posts only: pinned first, then the newest date shown; always NewsPost.PAGE_SIZE per page
    @Transactional(readOnly = true)
    public FeedPage feedPage(int page) {
        int safePage = Math.max(0, Math.min(page, MAX_PAGE));
        Slice<NewsPost> slice = postRepo.findByPublishedTrueOrderByPinnedDescPostDateDescIdDesc(PageRequest.of(safePage, NewsPost.PAGE_SIZE));
        return new FeedPage(views(slice.getContent()), slice.hasNext() ? safePage + 1 : null);
    }

    @Transactional(readOnly = true)
    public List<PostView> adminList() {
        return views(postRepo.findAllByOrderByPostDateDescIdDesc());
    }

    @Transactional(readOnly = true)
    public PostView find(Long id) {
        if (id == null) return null;
        return postRepo.findById(id).map(p -> views(List.of(p)).get(0)).orElse(null);
    }

    private List<PostView> views(List<NewsPost> posts) {
        if (posts.isEmpty()) return List.of();
        Map<Long, List<NewsPhoto>> byPost = new LinkedHashMap<>();
        posts.forEach(p -> byPost.put(p.getId(), new ArrayList<>()));
        for (NewsPhoto photo : photoRepo.findByPostIdInOrderByPostIdAscDisplayOrderAscIdAsc(byPost.keySet())) {
            List<NewsPhoto> list = byPost.get(photo.getPost().getId());
            if (list != null) list.add(photo);
        }
        List<PostView> views = new ArrayList<>();
        for (NewsPost post : posts) {
            List<NewsPhoto> photos = byPost.get(post.getId());
            List<PhotoView> photoViews = new ArrayList<>();
            for (int i = 0; i < photos.size(); i++) {
                NewsPhoto ph = photos.get(i);
                String alt = ph.getAltText() != null && !ph.getAltText().isBlank() ? ph.getAltText()
                        : "Photo " + (i + 1) + " of " + photos.size() + ", PAF Aerospace Museum news, "
                          + (post.getPostDate() == null ? "" : DATE_LABEL.format(post.getPostDate()));
                photoViews.add(new PhotoView(ph.getId(), ph.getFilename(), ph.getAltText(), alt, ph.getWidth(), ph.getHeight()));
            }
            views.add(new PostView(post.getId(), post.getCaption(), post.getPostDate(), post.isPublished(), post.isPinned(),
                    List.copyOf(photoViews)));
        }
        return views;
    }

    // ---------- Director edits ----------

    public static class NewsException extends RuntimeException {
        public NewsException(String message) { super(message); }
    }

    public enum SaveAction { DRAFT, PUBLISH, KEEP }

    // Creates (id == null) or updates a post: caption, date shown, photo order, alt texts and removed photos.
    // DRAFT hides it, PUBLISH shows it (needs at least one photo), KEEP leaves it as it was (a new post starts as a draft).
    @Transactional
    public Long save(Long id, String caption, String postDate, SaveAction action,
                     List<Long> photoOrder, Map<Long, String> altById, Collection<Long> removeIds) {
        String cleanCaption = cleanCaption(caption);
        LocalDate date = parseDate(postDate);
        Map<Long, String> cleanAlts = new LinkedHashMap<>();
        if (altById != null) {
            for (Map.Entry<Long, String> e : altById.entrySet()) cleanAlts.put(e.getKey(), cleanAlt(e.getValue()));
        }

        NewsPost post;
        if (id == null) {
            post = new NewsPost(cleanCaption, date, LocalDateTime.now(ClockConfig.or(clock)));
        } else {
            post = postRepo.findById(id).orElseThrow(() -> new NewsException("That post no longer exists."));
            post.setCaption(cleanCaption);
            post.setPostDate(date);
        }
        post = postRepo.save(post);

        List<NewsPhoto> photos = new ArrayList<>(id == null ? List.of() : photoRepo.findByPostIdOrderByDisplayOrderAscIdAsc(post.getId()));
        List<String> removedFiles = new ArrayList<>();
        Set<Long> remove = removeIds == null ? Set.of() : Set.copyOf(removeIds);
        List<NewsPhoto> kept = new ArrayList<>();
        for (NewsPhoto photo : photos) {
            if (remove.contains(photo.getId())) {
                removedFiles.add(photo.getFilename());
                photoRepo.delete(photo);
            } else {
                kept.add(photo);
            }
        }
        List<NewsPhoto> ordered = reorder(kept, photoOrder);
        for (int i = 0; i < ordered.size(); i++) {
            NewsPhoto photo = ordered.get(i);
            photo.setDisplayOrder(i + 1);
            if (cleanAlts.containsKey(photo.getId())) photo.setAltText(cleanAlts.get(photo.getId()));
        }
        photoRepo.saveAll(ordered);

        if (action == SaveAction.PUBLISH) {
            if (ordered.isEmpty()) throw new NewsException("Add at least one photo before publishing this post.");
            post.setPublished(true);
        } else if (action == SaveAction.DRAFT) {
            post.setPublished(false);
        }
        if (post.isPublished() && ordered.isEmpty()) post.setPublished(false); // every photo removed: back to draft
        postRepo.save(post);
        removedFiles.forEach(this::deleteQuietly);
        return post.getId();
    }

    // The listed ids first, in that order; any photo not listed keeps its place after them. Unknown ids are ignored.
    static List<NewsPhoto> reorder(List<NewsPhoto> photos, List<Long> order) {
        if (order == null || order.isEmpty()) return new ArrayList<>(photos);
        List<NewsPhoto> result = new ArrayList<>();
        for (Long wanted : order) {
            for (NewsPhoto p : photos) {
                if (p.getId().equals(wanted) && !result.contains(p)) result.add(p);
            }
        }
        for (NewsPhoto p : photos) if (!result.contains(p)) result.add(p);
        return result;
    }

    // Adds one photo (checked, downsized, re-encoded) at the end of the post; at most NewsPost.MAX_PHOTOS per post
    @Transactional
    public PhotoView addPhoto(Long postId, MultipartFile file, String alt) throws IOException {
        NewsPost post = postId == null ? null : postRepo.findById(postId).orElse(null);
        if (post == null) throw new NewsException("That post no longer exists.");
        String cleanAlt = cleanAlt(alt);
        List<NewsPhoto> photos = photoRepo.findByPostIdOrderByDisplayOrderAscIdAsc(postId);
        if (photos.size() >= NewsPost.MAX_PHOTOS) {
            throw new NewsException("A post can have at most " + NewsPost.MAX_PHOTOS + " photos.");
        }
        NewsPhotoStorage.StoredPhoto stored = photoStorage.store(file);
        try {
            int next = photos.stream().mapToInt(NewsPhoto::getDisplayOrder).max().orElse(0) + 1;
            NewsPhoto photo = new NewsPhoto(post, stored.filename(), next, stored.width(), stored.height());
            photo.setAltText(cleanAlt);
            photo = photoRepo.save(photo);
            return new PhotoView(photo.getId(), photo.getFilename(), photo.getAltText(),
                    cleanAlt != null ? cleanAlt : "Photo " + (photos.size() + 1), photo.getWidth(), photo.getHeight());
        } catch (RuntimeException e) {
            deleteQuietly(stored.filename()); // no row points at it: don't leave the file behind
            throw e;
        }
    }

    // Shows (true) or hides (false) the post on the visitor portal; showing needs at least one photo
    @Transactional
    public NewsPost setPublished(Long id, boolean published) {
        NewsPost post = postRepo.findById(id).orElseThrow(() -> new NewsException("That post no longer exists."));
        if (published && photoRepo.countByPostId(id) == 0) {
            throw new NewsException("Add at least one photo before publishing this post.");
        }
        post.setPublished(published);
        return postRepo.save(post);
    }

    @Transactional
    public NewsPost setPinned(Long id, boolean pinned) {
        NewsPost post = postRepo.findById(id).orElseThrow(() -> new NewsException("That post no longer exists."));
        post.setPinned(pinned);
        return postRepo.save(post);
    }

    // Deletes the post, its photo rows and their files (local and S3); false when it was already gone
    @Transactional
    public boolean delete(Long id) {
        NewsPost post = postRepo.findById(id).orElse(null);
        if (post == null) return false;
        List<NewsPhoto> photos = photoRepo.findByPostIdOrderByDisplayOrderAscIdAsc(id);
        List<String> files = photos.stream().map(NewsPhoto::getFilename).toList();
        photoRepo.deleteAll(photos);
        postRepo.delete(post);
        files.forEach(this::deleteQuietly);
        return true;
    }

    private void deleteQuietly(String filename) {
        try {
            photoStorage.delete(filename);
        } catch (IOException | RuntimeException e) {
            System.err.println("⚠️ [NEWS] Could not delete photo file " + filename + ": " + e.getMessage());
        }
    }

    // ---------- Input rules ----------

    // Plain text: Windows/Mac line breaks become \n, outer whitespace is trimmed, at most 2,200 characters. Never HTML.
    static String cleanCaption(String caption) {
        String text = caption == null ? "" : caption.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.length() > NewsPost.MAX_CAPTION) {
            throw new NewsException("The caption can be at most 2,200 characters (it has " + String.format("%,d", text.length()) + ").");
        }
        return text.isEmpty() ? null : text;
    }

    static String cleanAlt(String alt) {
        if (alt == null) return null;
        String text = alt.strip().replaceAll("\\s+", " ");
        if (text.length() > NewsPhoto.MAX_ALT) {
            throw new NewsException("A photo description (alt text) can be at most " + NewsPhoto.MAX_ALT + " characters.");
        }
        return text.isEmpty() ? null : text;
    }

    // Today on the shared clock: the editor's default "date shown"
    public LocalDate today() {
        return LocalDate.now(ClockConfig.or(clock));
    }

    LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return today();
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new NewsException("Please enter a valid date for the post.");
        }
    }
}
