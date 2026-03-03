package org.mat.command;

import net.dv8tion.jda.api.entities.Message;
import org.mat.tool.DBManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseCommand implements ICommand {
    protected final String name;
    protected final DBManager db;
    protected final String[] aliases;
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    public BaseCommand(String name, DBManager db, String... aliases) {
        this.name = name;
        this.db = db;
        this.aliases = aliases;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String[] getAliases() {
        return aliases;
    }

    protected long resolveTargetId(Message message, String content) {
        if (message.getReferencedMessage() != null) {
            return message.getReferencedMessage().getIdLong();
        }
        try {
            return Long.parseLong(content.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Returns the next argument and the remainder.<br>
     * For example, for an input {@code key v1 v2 v3}, it returns {@code {"key", "v1 v2 v3"}}.
     * @param args - A command body to be processed.
     * @return
     * {@code args[0]} - The next argument.<br>
     * {@code args[1]} - The remainder of body.
     */
    protected String[] nextArg(String args) {
        if (args == null) return new String[] {};
        return args.trim().split("\\s+", 2);
    }

}
