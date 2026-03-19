package com.chat.service;

import com.chat.model.Group;
import com.chat.repository.GroupRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final MessagingService messagingService;

    @PostConstruct
    public void init() {
        // Ensure the shared membership topic exchange exists before any WS session starts
        messagingService.ensureMembershipExchange();
    }

    public Mono<Group> createGroup(String creatorId, String name, List<String> memberIds) {
        var group = new Group();
        group.setId(UUID.randomUUID().toString());
        group.setName(name);
        group.setCreatedBy(creatorId);
        // Always include the creator
        if (!memberIds.contains(creatorId)) memberIds.add(0, creatorId);
        group.setMemberIds(memberIds);

        return groupRepository.save(group)
            .doOnSuccess(g -> {
                // Declare the fan-out exchange for this group right away
                messagingService.ensureGroupExchange(g.getId());
                log.info("Group '{}' created with {} members", g.getName(), g.getMemberIds().size());
            });
    }

    /** Add a user to an existing group and bind their future sessions to the exchange. */
    public Mono<Group> addMember(String groupId, String userId) {
        return groupRepository.findById(groupId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Group not found: " + groupId)))
            .flatMap(group -> {
                if (!group.hasMember(userId)) {
                    group.getMemberIds().add(userId);
                }
                return groupRepository.save(group);
            })
            .doOnSuccess(g -> {
                log.info("User {} added to group {}", userId, groupId);
                // Notify any active WS session for this user so it can
                // dynamically subscribe to the new group channel without reconnecting
                messagingService.publishMembershipEvent(userId, groupId);
            });
    }

    public Mono<Group> removeMember(String groupId, String userId) {
        return groupRepository.findById(groupId)
            .flatMap(group -> {
                group.getMemberIds().remove(userId);
                return groupRepository.save(group);
            });
    }

    public Mono<Group> findById(String groupId) {
        return groupRepository.findById(groupId);
    }

    /** All groups the user is part of — for populating the sidebar. */
    public Flux<Group> groupsForUser(String userId) {
        return groupRepository.findByMemberIdsContaining(userId);
    }
}
