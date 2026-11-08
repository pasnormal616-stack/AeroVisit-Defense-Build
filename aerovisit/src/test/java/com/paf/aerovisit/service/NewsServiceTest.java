package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;

import com.paf.aerovisit.model.NewsPhoto;
import com.paf.aerovisit.model.NewsPost;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.NewsPhotoRepository;
import com.paf.aerovisit.repository.NewsPostRepository;
import com.paf.aerovisit.service.NewsService.SaveAction;

/** The News feed rules on in-memory H2 (never the real database) with real photo files in a temp folder. */
@DataJpaTest
@Import({NewsService.class, NewsServiceTest.Storage.class})
class NewsServiceTest {

    static final Path DIR;
    static {
        try {
            DIR = Files.createTempDirectory("news-photos-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration
    static class Storage {
        @Bean
        ExhibitPhotoStorage exhibitPhotoStorage(ExhibitPhotoRepository repo) {
            return new ExhibitPhotoStorage(repo, DIR.toString());
        }

        @Bean
        NewsPhotoStorage newsPhotoStorage(ExhibitPhotoStorage storage) {
            return new NewsPhotoStorage(storage);
        }
    }

    @Autowired private NewsService news;
    @Autowired private NewsPostRepository postRepo;
    @Autowired private NewsPhotoRepository photoRepo;

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    private static MockMultipartFile jpg(String name) {
        try {
            return new MockMultipartFile("photo", name, "image/jpeg", NewsPhotoStorageTest.image("jpg", 120, 80, false));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // A post saved straight to the repositories: published or not, pinned or not, with one photo row
    private NewsPost post(String caption, LocalDate date, boolean published, boolean pinned) {
        NewsPost p = new NewsPost(caption, date, LocalDateTime.of(2026, 11, 1, 9, 0));
        p.setPublished(published);
        p.setPinned(pinned);
        p = postRepo.save(p);
        photoRepo.save(new NewsPhoto(p, "f-" + p.getId() + ".jpg", 1, 120, 80));
        return p;
    }

    private static long filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    // ---------- The visitor feed ----------

    @Test
    void theFeedShowsPublishedPostsOnlyPinnedFirstThenNewestFivePerPage() {
        for (int d = 1; d <= 7; d++) post("Day " + d, DAY.plusDays(d), true, d == 2);
        post("Draft, newest of all", DAY.plusDays(30), false, true);

        NewsService.FeedPage first = news.feedPage(0);
        assertThat(first.posts()).extracting(NewsService.PostView::caption)
                .containsExactly("Day 2", "Day 7", "Day 6", "Day 5", "Day 4");
        assertThat(first.posts()).hasSize(NewsPost.PAGE_SIZE);
        assertThat(first.nextPage()).isEqualTo(1);
        assertThat(first.posts().get(0).photos()).hasSize(1);

        NewsService.FeedPage second = news.feedPage(1);
        assertThat(second.posts()).extracting(NewsService.PostView::caption).containsExactly("Day 3", "Day 1");
        assertThat(second.nextPage()).isNull();
    }

    @Test
    void aBadPageNumberNeverBreaksTheFeed() {
        post("Only", DAY, true, false);
        assertThat(news.feedPage(-4).posts()).extracting(NewsService.PostView::caption).containsExactly("Only");
        assertThat(news.feedPage(Integer.MAX_VALUE).posts()).isEmpty();
    }

    @Test
    void photosComeInTheirOrderWithADescriptiveAltWhenNoneWasGiven() {
        NewsPost p = postRepo.save(published(new NewsPost("Two photos", DAY, LocalDateTime.now())));
        NewsPhoto second = new NewsPhoto(p, "b.jpg", 2, 10, 10);
        NewsPhoto first = new NewsPhoto(p, "a.jpg", 1, 10, 10);
        first.setAltText("F-5A on the apron");
        photoRepo.save(second);
        photoRepo.save(first);

        List<NewsService.PhotoView> photos = news.feedPage(0).posts().get(0).photos();
        assertThat(photos).extracting(NewsService.PhotoView::url).containsExactly("/exhibit-photos/a.jpg", "/exhibit-photos/b.jpg");
        assertThat(photos.get(0).alt()).isEqualTo("F-5A on the apron");
        assertThat(photos.get(1).alt()).isEqualTo("Photo 2 of 2, PAF Aerospace Museum news, November 1, 2026");
    }

    private static NewsPost published(NewsPost p) {
        p.setPublished(true);
        return p;
    }

    // ---------- Create / edit ----------

    @Test
    void aNewPostIsADraftWithItsLineBreaksAndTodayAsTheDefaultDate() {
        Long id = news.save(null, "  Line one\r\nLine two\r\n\r\n<b>not bold</b>  ", "", SaveAction.KEEP, null, null, null);

        NewsPost saved = postRepo.findById(id).orElseThrow();
        assertThat(saved.isPublished()).isFalse();
        assertThat(saved.getCaption()).isEqualTo("Line one\nLine two\n\n<b>not bold</b>"); // plain text, kept as typed
        assertThat(saved.getPostDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void aCaptionOver2200CharactersIsRefused() {
        assertThat(news.save(null, "x".repeat(2200), "2026-11-06", SaveAction.DRAFT, null, null, null)).isNotNull();
        assertThatThrownBy(() -> news.save(null, "x".repeat(2201), "2026-11-06", SaveAction.DRAFT, null, null, null))
                .isInstanceOf(NewsService.NewsException.class)
                .hasMessageContaining("2,200");
    }

    @Test
    void aBadDateIsRefused() {
        assertThatThrownBy(() -> news.save(null, "x", "06/11/2026", SaveAction.DRAFT, null, null, null))
                .hasMessageContaining("valid date");
    }

    @Test
    void publishingNeedsAtLeastOnePhoto() {
        Long id = news.save(null, "No photos", "2026-11-06", SaveAction.DRAFT, null, null, null);
        assertThatThrownBy(() -> news.setPublished(id, true)).hasMessageContaining("at least one photo");
        assertThatThrownBy(() -> news.save(id, "No photos", "2026-11-06", SaveAction.PUBLISH, null, null, null))
                .hasMessageContaining("at least one photo");
    }

    @Test
    void editPublishUnpublishAndPin() throws Exception {
        Long id = news.save(null, "Open day", "2026-11-06", SaveAction.DRAFT, null, null, null);
        news.addPhoto(id, jpg("a.jpg"), "Visitors at the hangar");

        news.save(id, "Open day, updated", "2026-11-07", SaveAction.PUBLISH, null, null, null);
        NewsPost p = postRepo.findById(id).orElseThrow();
        assertThat(p.isPublished()).isTrue();
        assertThat(p.getCaption()).isEqualTo("Open day, updated");
        assertThat(p.getPostDate()).isEqualTo(LocalDate.of(2026, 11, 7));

        news.setPublished(id, false);
        assertThat(postRepo.findById(id).orElseThrow().isPublished()).isFalse();
        news.setPinned(id, true);
        assertThat(postRepo.findById(id).orElseThrow().isPinned()).isTrue();
        news.setPinned(id, false);
        assertThat(postRepo.findById(id).orElseThrow().isPinned()).isFalse();
    }

    @Test
    void reorderAltTextAndRemovingAPhotoDeletesItsFile() throws Exception {
        Long id = news.save(null, "Three photos", "2026-11-06", SaveAction.DRAFT, null, null, null);
        Long a = news.addPhoto(id, jpg("a.jpg"), null).id();
        Long b = news.addPhoto(id, jpg("b.jpg"), null).id();
        Long c = news.addPhoto(id, jpg("c.jpg"), null).id();
        String fileOfB = photoRepo.findById(b).orElseThrow().getFilename();
        assertThat(Files.exists(DIR.resolve(fileOfB))).isTrue();

        news.save(id, "Three photos", "2026-11-06", SaveAction.PUBLISH, List.of(c, a, b), Map.of(c, "  The C-130  "), List.of(b));

        List<NewsPhoto> left = photoRepo.findByPostIdOrderByDisplayOrderAscIdAsc(id);
        assertThat(left).extracting(NewsPhoto::getId).containsExactly(c, a);
        assertThat(left).extracting(NewsPhoto::getDisplayOrder).containsExactly(1, 2);
        assertThat(left.get(0).getAltText()).isEqualTo("The C-130");
        assertThat(Files.exists(DIR.resolve(fileOfB))).isFalse();
    }

    @Test
    void removingEveryPhotoOfAPublishedPostMakesItADraftAgain() throws Exception {
        Long id = news.save(null, "One", "2026-11-06", SaveAction.DRAFT, null, null, null);
        Long a = news.addPhoto(id, jpg("a.jpg"), null).id();
        news.setPublished(id, true);

        news.save(id, "One", "2026-11-06", SaveAction.KEEP, null, null, List.of(a));
        assertThat(postRepo.findById(id).orElseThrow().isPublished()).isFalse();
    }

    // ---------- Photos ----------

    @Test
    void aPostTakesAtMostTenPhotosAndTheEleventhLeavesNoFileBehind() throws Exception {
        Long id = news.save(null, "Ten", "2026-11-06", SaveAction.DRAFT, null, null, null);
        for (int i = 0; i < NewsPost.MAX_PHOTOS; i++) news.addPhoto(id, jpg("p" + i + ".jpg"), null);
        long files = filesIn(DIR);

        assertThatThrownBy(() -> news.addPhoto(id, jpg("eleven.jpg"), null))
                .isInstanceOf(NewsService.NewsException.class)
                .hasMessageContaining("at most 10 photos");
        assertThat(photoRepo.countByPostId(id)).isEqualTo(10);
        assertThat(filesIn(DIR)).isEqualTo(files);
    }

    @Test
    void aBadFileIsRefusedAndNothingIsSaved() throws Exception {
        Long id = news.save(null, "Bad", "2026-11-06", SaveAction.DRAFT, null, null, null);
        long files = filesIn(DIR);

        assertThatThrownBy(() -> news.addPhoto(id, new MockMultipartFile("photo", "x.jpg", "image/jpeg", "not an image".getBytes()), null))
                .isInstanceOf(ExhibitPhotoStorage.InvalidImageException.class);
        assertThatThrownBy(() -> news.addPhoto(id, new MockMultipartFile("photo", "x.svg", "image/svg+xml", "<svg/>".getBytes()), null))
                .isInstanceOf(ExhibitPhotoStorage.InvalidImageException.class);
        assertThat(photoRepo.countByPostId(id)).isZero();
        assertThat(filesIn(DIR)).isEqualTo(files);
    }

    @Test
    void anAltTextOver250CharactersIsRefused() {
        Long id = news.save(null, "Alt", "2026-11-06", SaveAction.DRAFT, null, null, null);
        assertThatThrownBy(() -> news.addPhoto(id, jpg("a.jpg"), "a".repeat(251))).hasMessageContaining("250");
    }

    @Test
    void deletingAPostRemovesItsPhotosAndTheirFiles() throws Exception {
        Long id = news.save(null, "Gone", "2026-11-06", SaveAction.DRAFT, null, null, null);
        news.addPhoto(id, jpg("a.jpg"), null);
        news.addPhoto(id, jpg("b.jpg"), null);
        List<String> names = photoRepo.findByPostIdOrderByDisplayOrderAscIdAsc(id).stream().map(NewsPhoto::getFilename).toList();

        assertThat(news.delete(id)).isTrue();

        assertThat(postRepo.findById(id)).isEmpty();
        assertThat(photoRepo.countByPostId(id)).isZero();
        names.forEach(n -> assertThat(Files.exists(DIR.resolve(n))).isFalse());
        assertThat(news.delete(id)).isFalse();
    }

    @Test
    void theAdminListShowsEveryPostNewestFirst() {
        post("Older", DAY, true, true);
        post("Newer draft", DAY.plusDays(3), false, false);
        assertThat(news.adminList()).extracting(NewsService.PostView::caption).containsExactly("Newer draft", "Older");
    }
}
