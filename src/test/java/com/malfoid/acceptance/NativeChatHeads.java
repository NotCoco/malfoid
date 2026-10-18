package com.malfoid.acceptance;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import static org.mockito.Mockito.*;

/** The pinned native callback copies the exact current channel head, raw value and timestamp. */
final class NativeChatHeads
{
    private final Map<Integer, ChatLineBuffer> buffers = new HashMap<>();
    private final Map<Integer, Channel> channels = new HashMap<>();
    private final Map<Integer, Emission> emissions = new HashMap<>();

    NativeChatHeads(Client client) { when(client.getChatLineMap()).thenReturn(buffers); }

    ChatMessage message(int id, ChatMessageType type, String raw)
    {
        Emission emission = emissions.get(id);
        if (emission == null)
        {
            emission = new Emission(id, type, raw); emissions.put(id, emission);
            Channel channel = channels.get(type.getType());
            if (channel == null)
            {
                channel = new Channel(); channels.put(type.getType(), channel); buffers.put(type.getType(), channel.buffer);
            }
            channel.insert(emission.node);
        }
        else if (emission.type != type || !emission.raw.equals(raw))
        {
            // Model conflicting current metadata without promoting an old slot into a new insertion.
            emission.type = type; emission.raw = raw;
        }
        // A saved replay uses its original slot and never moves it to the native head.
        ChatMessage event = mock(ChatMessage.class);
        when(event.getMessageNode()).thenReturn(emission.node); when(event.getType()).thenReturn(type);
        when(event.getMessage()).thenReturn(raw); when(event.getTimestamp()).thenReturn(100); return event;
    }

    private static final class Channel
    {
        final ChatLineBuffer buffer = mock(ChatLineBuffer.class);
        final MessageNode[] lines = new MessageNode[100];
        int length;
        Channel() { when(buffer.getLines()).thenReturn(lines); when(buffer.getLength()).thenAnswer(call -> length); }
        void insert(MessageNode node)
        {
            System.arraycopy(lines, 0, lines, 1, Math.min(length, lines.length - 1));
            lines[0] = node; length = Math.min(length + 1, lines.length);
        }
    }

    private static final class Emission
    {
        final MessageNode node = mock(MessageNode.class);
        ChatMessageType type;
        String raw;
        Emission(int id, ChatMessageType type, String raw)
        {
            this.type = type; this.raw = raw;
            when(node.getId()).thenReturn(id); when(node.getTimestamp()).thenReturn(100);
            when(node.getType()).thenAnswer(call -> this.type); when(node.getValue()).thenAnswer(call -> this.raw);
        }
    }
}
