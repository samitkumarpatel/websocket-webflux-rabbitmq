package com.chat.repository;

import com.chat.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

public interface ChatMessageRepository extends ReactiveMongoRepository<ChatMessage, String> {

    // DM history (both directions)
    @Query("{ $or: [ { senderId:?0, recipientId:?1 }, { senderId:?1, recipientId:?0 } ] }")
    Flux<ChatMessage> findDmHistory(String a, String b, Pageable p);

    // Group history
    Flux<ChatMessage> findByGroupIdOrderBySentAtDesc(String groupId, Pageable p);
}
