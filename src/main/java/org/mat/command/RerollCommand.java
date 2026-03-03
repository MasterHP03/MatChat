package org.mat.command;

import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.ChatService;
import org.mat.tool.DBManager;

import java.util.concurrent.CompletableFuture;

public class RerollCommand extends ThreadCommand {
    private final ChatService chat;

    public RerollCommand(DBManager db, ChatService chat) {
        super("reroll",
                db,
                "regen");
        this.chat = chat;
    }

    @Override
    public void handleThread(MessageReceivedEvent event, ThreadChannel thread, long sessionId, String args) {
        CompletableFuture.runAsync(() -> chat.reroll(sessionId, event.getMessage()));
    }
}
