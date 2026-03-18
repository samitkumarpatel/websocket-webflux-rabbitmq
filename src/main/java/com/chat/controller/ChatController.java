package com.chat.controller;

import com.chat.model.ChatMessage;
import com.chat.service.GroupService;
import com.chat.service.MessagingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ChatController {

    private final GroupService groupService;
    private final MessagingService messagingService;

    /** POST /api/groups  { name, createdBy, memberIds[] } */
    public Mono<ServerResponse> createGroup(ServerRequest req) {
        return req.bodyToMono(CreateGroupRequest.class)
            .flatMap(b -> groupService.createGroup(b.createdBy(), b.name(), b.memberIds()))
            .flatMap(g -> ServerResponse.ok().bodyValue(g));
    }

    /** POST /api/groups/{groupId}/members  { userId } */
    public Mono<ServerResponse> addMember(ServerRequest req) {
        String groupId = req.pathVariable("groupId");
        return req.bodyToMono(MemberRequest.class)
            .flatMap(b -> groupService.addMember(groupId, b.userId()))
            .flatMap(g -> ServerResponse.ok().bodyValue(g));
    }

    /** DELETE /api/groups/{groupId}/members/{userId} */
    public Mono<ServerResponse> removeMember(ServerRequest req) {
        return groupService.removeMember(
                req.pathVariable("groupId"),
                req.pathVariable("userId"))
            .flatMap(g -> ServerResponse.ok().bodyValue(g));
    }

    /** GET /api/groups?userId=alice — groups for a user */
    public Mono<ServerResponse> listGroups(ServerRequest req) {
        String userId = req.queryParam("userId").orElseThrow();
        return ServerResponse.ok()
            .body(groupService.groupsForUser(userId), Object.class);
    }

    /** GET /api/history/dm/{a}/{b}?limit=50 */
    public Mono<ServerResponse> dmHistory(ServerRequest req) {
        return ServerResponse.ok().body(
            messagingService.dmHistory(
                req.pathVariable("a"),
                req.pathVariable("b"),
                Integer.parseInt(req.queryParam("limit").orElse("50"))
            ), ChatMessage.class);
    }

    /** GET /api/history/group/{groupId}?limit=50 */
    public Mono<ServerResponse> groupHistory(ServerRequest req) {
        return ServerResponse.ok().body(
            messagingService.groupHistory(
                req.pathVariable("groupId"),
                Integer.parseInt(req.queryParam("limit").orElse("50"))
            ), ChatMessage.class);
    }

    public record CreateGroupRequest(String name, String createdBy, List<String> memberIds) {}
    public record MemberRequest(String userId) {}
}
