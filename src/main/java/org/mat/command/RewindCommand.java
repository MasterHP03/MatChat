package org.mat.command;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public class RewindCommand extends ThreadCommand {
    public RewindCommand(DBManager db) {
        super("rewind",
                db,
                "drop");
    }

    @Override
    public void handleThread(MessageReceivedEvent event, ThreadChannel thread, long sessionId, String args) {
        User user = event.getAuthor();
        Message message = event.getMessage();

        String id = args.isBlank() ? "" : args;

        long targetId = resolveTargetId(message, id);
        if (targetId == -1) {
            message.reply("되감을 메시지의 답장으로 사용하거나, ID를 입력하세요.").queue();
            return;
        }
        if (!db.isMessageExists(sessionId, targetId)) {
            message.reply("유효한 메시지 ID가 아닙니다.").queue();
            return;
        }

        int count = db.rewindSession(sessionId, targetId);
        message.reply(count + "개를 삭제하고 메시지를 되감았습니다.").queue();
    }
}
