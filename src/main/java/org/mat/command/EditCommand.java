package org.mat.command;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public class EditCommand extends ThreadCommand {
    public EditCommand(DBManager db) {
        super("edit",
                db);
    }

    @Override
    public void handleThread(MessageReceivedEvent event, ThreadChannel thread, long sessionId, String args) {
        Message message = event.getMessage();
        Message refMsg = message.getReferencedMessage();
        if (refMsg == null) {
            message.reply("수정할 봇 메시지에 답장을 달아주세요.").queue();
            return;
        }
        if (args.isBlank()) {
            message.reply("사용법: `$edit <메시지>` (답장으로 사용)").queue();
            return;
        }

        String content = String.join(" ", args);

        // 메시지가 너무 길어지면 디스코드 2000자 제한에 걸려서, 메시지를 나눠보낼 예정
        // 그런 경우, 메시지 수정을 할 때 2000자 이상이라서 메시지 수정이 안 되는 일이 생길 수 있음.
        // 따라서 디스코드상에서 수정되지 않더라도 수정이 가능하도록 처리
        db.editMessage(sessionId, refMsg.getIdLong(), content);
        message.addReaction(Emoji.fromUnicode("✅")).queue();

        // 봇인 경우에만 수정 시도. (replace와 달리 edit은 blank 검사를 이미 했으니 2000자 검사만 있어도 됨)
        if (refMsg.getAuthor().isBot()) {
            if (!content.isBlank() && content.length() <= 2000) {
                refMsg.editMessage(content).queue();
            } else {
                message.reply("수정 실패: 길이가 1~2000자 내에 있지 않습니다. (DB에는 저장)").queue();
            }
        }
    }
}
