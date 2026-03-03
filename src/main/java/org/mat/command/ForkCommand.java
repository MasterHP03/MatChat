package org.mat.command;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public class ForkCommand extends LobbyCommand {
    public ForkCommand(DBManager db) {
        super("fork",
                db,
                "clone");
    }

    @Override
    public void handleLobby(MessageReceivedEvent event, TextChannel tc, String args) {
        User user = event.getAuthor();
        Message message = event.getMessage();

        String id = args.isBlank() ? "" : args;

        long targetId = resolveTargetId(message, id);
        if (targetId == -1) {
            message.reply("복제할 세션 메시지의 답장으로 사용하거나, ID를 입력하세요.").queue();
            return;
        }
        Message targetMsg = message.getReferencedMessage();
        if (targetMsg == null)  {
            try {
                targetMsg = tc.retrieveMessageById(targetId).complete();
            } catch (Exception e) {
                message.reply("유효한 메시지 ID가 아닙니다.").queue();
                return;
            }
        }
        ThreadChannel targetThread = targetMsg.getStartedThread();
        if (targetThread == null)  {
            message.reply("해당 메시지에는 세션 스레드가 없습니다.").queue();
            return;
        }
        String title = targetThread.getName() + "-복사본";

        message.createThreadChannel(title).queue(newThread ->
                db.forkSession(targetThread.getIdLong(), newThread.getIdLong(), user.getAsTag(), title));
    }
}
