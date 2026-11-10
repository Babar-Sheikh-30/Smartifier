package com.bytesolutions.smartifier.content;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface ContentStackRepository extends MongoRepository<ContentStack, String> {
    List<ContentStack> findByDefaultStackTrue();
    List<ContentStack> findByOwnerId(String ownerId);
    Optional<ContentStack> findByIdAndOwnerId(String id, String ownerId);
    @Query("{'defaultStack': false, 'archived': {$ne: true}, 'versions.status': 'PENDING_REVIEW'}")
    List<ContentStack> findPendingReviews();
}
