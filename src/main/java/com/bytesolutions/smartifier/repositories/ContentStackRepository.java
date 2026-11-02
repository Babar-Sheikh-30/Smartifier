package com.bytesolutions.smartifier.repositories;

import java.util.List;
import java.util.Optional;
import com.bytesolutions.smartifier.models.ContentStack;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ContentStackRepository extends MongoRepository<ContentStack, String> {
    List<ContentStack> findByDefaultStackTrue();
    List<ContentStack> findByOwnerId(String ownerId);
    Optional<ContentStack> findByIdAndOwnerId(String id, String ownerId);
}
