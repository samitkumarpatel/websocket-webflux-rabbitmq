package com.chat.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "messages")
@CompoundIndexes({
    // Fast DM history lookup (both directions)
    @CompoundIndex(name = "dm_idx",    def = "{'senderId':1,'recipientId':1,'sentAt':-1}"),
    // Fast group history lookup
    @CompoundIndex(name = "group_idx", def = "{'groupId':1,'sentAt':-1}")
})
public class ChatMessage {

    @Id
    private String id;

    private MessageType type;   // DM or GROUP

    private String senderId;

    // DM fields
    private String recipientId; // null for group messages

    // Group fields
    private String groupId;     // null for DM messages

    private String content;
    private Instant sentAt = Instant.now();

    public enum MessageType { DM, GROUP }
}
