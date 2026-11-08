package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.NewsPost;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NewsPostRepository extends JpaRepository<NewsPost, Long> {

    // The visitor feed: published only, pinned first, then the newest date shown (then newest id). A Slice: no count query.
    Slice<NewsPost> findByPublishedTrueOrderByPinnedDescPostDateDescIdDesc(Pageable page);

    // Website Content > News: every post, newest first
    List<NewsPost> findAllByOrderByPostDateDescIdDesc();
}
