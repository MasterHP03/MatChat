package org.mat.tool;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.command.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class CommandManager {
    private final Map<String, ICommand> commands = new HashMap<>();
    private final DBManager db;
    private final ChatService chat;
    private final Logger logger = LoggerFactory.getLogger(CommandManager.class);

    public CommandManager(DBManager db, ChatService chat) {
        this.db = db;
        this.chat = chat;
        register(new CreateCommand(db));
        register(new ForkCommand(db));
        register(new EditCommand(db));
        register(new ReplaceCommand(db));
        register(new DeleteCommand(db));
        register(new RewindCommand(db));
        register(new SetCommand(db));
        register(new RerollCommand(db, chat));
    }

    private void register(ICommand cmd) {
        commands.put(cmd.getName().toLowerCase(), cmd);

        for (String alias : cmd.getAliases()) {
            if (commands.containsKey(alias)) {
                logger.warn("""
                        커맨드 등록 중에 alias 충돌이 발생하였습니다.
                        충돌이 발생한 alias: {}
                        Original Cmd: {}
                        Input Cmd: {}
                        """,
                        alias,
                        commands.get(alias).getName(),
                        cmd.getName());
                continue;
            }
            commands.put(alias.toLowerCase(), cmd);
        }
    }

    public void handle(MessageReceivedEvent event) {
        String msg = event.getMessage().getContentRaw();
        String body = msg.substring(Config.getPrefix().length());

        String[] split = body.trim().split("\\s+", 2);
        if (split.length == 0) return;

        String cmdName = split[0].toLowerCase();
        String args = split.length > 1 ? split[1] : "";

        if (commands.containsKey(cmdName)) {
            commands.get(cmdName).execute(event, args);
        }
    }

}
