package org.mat.command;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.tool.DBManager;

public class ReplaceCommand extends ThreadCommand {
    public ReplaceCommand(DBManager db) {
        super("replace",
                db,
                "repl");
    }

    @Override
    public void handleThread(MessageReceivedEvent event, ThreadChannel thread, long sessionId, String args) {
        Message message = event.getMessage();
        Message refMsg = message.getReferencedMessage();
        if (refMsg == null) {
            message.reply("문자열을 대체할 메시지에 답장을 달아주세요.").queue();
            return;
        }
        String[] splitRepl = args.split("[|]", -1);
        if (splitRepl.length != 2) {
            message.reply("사용법: `$repl[ace] <from>|<to>`").queue();
            return;
        }
        // 2000자 넘는 경우에 대비하여 메시지를 디스코드가 아닌 DB에서 로드함
        String toEdit = db.getMessage(sessionId, refMsg.getIdLong());
        String from_text = splitRepl[0], to_text = splitRepl[1];
        if (toEdit == null) {
            message.reply("유효한 메시지가 아닙니다.").queue();
            return;
        }
        int occurrence = getOccurrence(toEdit, from_text);
        if (occurrence <= 0) {
            message.reply("해당 텍스트를 찾을 수 없습니다.").queue();
            return;
        }
        String result = toEdit.replace(from_text, to_text);
        db.editMessage(sessionId, refMsg.getIdLong(), result);
        message.addReaction(Emoji.fromUnicode("✅")).queue();
        message.reply(occurrence + "회 수정 완료했습니다!").queue();

        // edit과 마찬가지 처리
        if (refMsg.getAuthor().isBot()) {
            if (!result.isBlank() && result.length() <= 2000) {
                refMsg.editMessage(result).queue();
            } else {
                message.reply("수정 실패: 길이가 1~2000자 내에 있지 않습니다. (DB에는 저장)").queue();
            }
        }
    }

    private int getOccurrence(String text, String toFind) {
        int occurrence = 0;
        int idx = 0;
        while ((idx = text.indexOf(toFind, idx)) != -1) {
            idx += toFind.length();
            occurrence++;
        }
        return occurrence;
    }
}
