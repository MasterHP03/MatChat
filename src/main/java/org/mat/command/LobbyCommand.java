package org.mat.command;

import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public abstract class LobbyCommand extends BaseCommand {

    public LobbyCommand(String name, DBManager db, String... aliases) {
        super(name, db, aliases);
    }

    @Override
    public final void execute(MessageReceivedEvent event, String args) {
        if (event.getChannelType() != ChannelType.TEXT) {
            return;
        }

        handleLobby(event, event.getChannel().asTextChannel(), args);
    }

    protected abstract void handleLobby(MessageReceivedEvent event, TextChannel tc, String args);

}
