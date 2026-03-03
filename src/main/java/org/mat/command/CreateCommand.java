package org.mat.command;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public class CreateCommand extends LobbyCommand {
    public CreateCommand(DBManager db) {
        super("create",
                db);
    }

    @Override
    public void handleLobby(MessageReceivedEvent event, TextChannel tc, String args) {
        User user = event.getAuthor();
        Message message = event.getMessage();

        if (args.isBlank()) {
            message.reply("사용법: `$create <세션명>`").queue();
            return;
        }

        String content = String.join(" ", args);

        message.createThreadChannel(content).queue(thread ->
                db.createSession(thread.getIdLong(), user.getAsTag(), content));
    }
}
