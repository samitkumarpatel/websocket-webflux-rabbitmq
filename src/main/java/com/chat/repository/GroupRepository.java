package com.chat.repository;

import com.chat.model.Group;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

public interface GroupRepository extends ReactiveMongoRepository<Group, String> {

    // All groups a user belongs to (for sidebar listing)
    Flux<Group> findByMemberIdsContaining(String userId);
}
