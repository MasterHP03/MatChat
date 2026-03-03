package org.mat.command;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public interface ICommand {
    void execute(MessageReceivedEvent event, String args);
    String getName();
    String[] getAliases();
}
