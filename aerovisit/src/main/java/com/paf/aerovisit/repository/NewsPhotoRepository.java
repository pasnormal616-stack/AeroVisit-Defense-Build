package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.NewsPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface NewsPhotoRepository extends JpaRepository<NewsPhoto, Long> {

    // All photos of one page of posts in one query (no N+1), each post's in its display order
    List<NewsPhoto> findByPostIdInOrderByPostIdAscDisplayOrderAscIdAsc(Collection<Long> postIds);

    List<NewsPhoto> findByPostIdOrderByDisplayOrderAscIdAsc(Long postId);

    long countByPostId(Long postId);
}
