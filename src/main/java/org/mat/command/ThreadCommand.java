package org.mat.command;

import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public abstract class ThreadCommand extends BaseCommand {
    public ThreadCommand(String name, DBManager db, String... aliases) {
        super(name, db, aliases);
    }

    @Override
    public final void execute(MessageReceivedEvent event, String args) {
        if (!event.getChannelType().isThread()) {
            return;
        }

        ThreadChannel thread = event.getChannel().asThreadChannel();
        long sessionId = thread.getIdLong();

        if (!db.isSessionExists(sessionId)) return;

        handleThread(event, thread, sessionId, args);
    }

    protected abstract void handleThread(MessageReceivedEvent event, ThreadChannel thread, long sessionId, String args);
}
