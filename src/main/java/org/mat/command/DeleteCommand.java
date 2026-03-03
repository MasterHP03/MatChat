package org.mat.command;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public class DeleteCommand extends ThreadCommand {
    public DeleteCommand(DBManager db) {
        super("delete",
                db,
                "remove", "del");
    }

    @Override
    public void handleThread(MessageReceivedEvent event, ThreadChannel thread, long sessionId, String args) {
        Message message = event.getMessage();
        Message refMsg = message.getReferencedMessage();
        if (refMsg == null) {
            message.reply("삭제할 봇 메시지에 답장을 달아주세요.").queue();
            return;
        }
        // 2000자로 나눠보내면 나눠보내진 메시지 중 2번째부터는 DB에는 없는 ID이므로 검사
        if (!db.isMessageExists(sessionId, refMsg.getIdLong())) {
            message.reply("유효한 메시지가 아닙니다.").queue();
            return;
        }

        // 여러 메시지 연쇄 삭제 구현은 아직 안 해뒀지만, Base 메시지는 사라지고 DB도 삭제됨
        refMsg.delete().queue(success -> {
            db.deleteMessage(sessionId, refMsg.getIdLong());
            message.addReaction(Emoji.fromUnicode("✅")).queue();
        }, error -> message.reply("메시지 삭제 실패").queue());
    }
}
