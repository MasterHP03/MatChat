package org.mat.command;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.mat.def.GeminiModel;
import org.mat.def.SessionSetting;
import org.mat.def.Tools;
import org.mat.tool.Config;
import org.mat.tool.DBManager;

import java.util.Arrays;
import java.util.regex.Pattern;

public class SetCommand extends ThreadCommand {
    public SetCommand(DBManager db) {
        super("set",
                db,
                "setting");
    }

    @Override
    public void handleThread(MessageReceivedEvent event, ThreadChannel thread, long sessionId, String args) {
        Message message = event.getMessage();
        if (args.isBlank()) {
            DBManager.SessionInfo info = db.getSessionInfo(sessionId);
            StringBuilder sb = new StringBuilder();
            for (SessionSetting setting : SessionSetting.values()) {
                sb.append(setting.getColumnName()).append(": ").append(info.get(setting)).append("\n");
            }
            for (Tools tool : Tools.values()) {
                sb.append(tool.getToolName()).append(": ").append(db.isToolEnabled(sessionId, tool)).append("\n");
            }
            message.reply(sb.toString()).queue();
            return;
        }

        String[] argSetting = nextArg(args);
        String key = argSetting[0];
        SessionSetting setting = SessionSetting.from(key);
        Tools tool = Tools.from(key);
        if (setting != null) {
            if (argSetting.length < 2) {
                DBManager.SessionInfo info = db.getSessionInfo(sessionId);
                message.reply("""
                    %s: %s
                    """.formatted(setting.getColumnName(), info.get(setting))).queue();
                return;
            }

            String value = argSetting[1];
            if (setting == SessionSetting.PERSONA) {
                if (!Config.isValidPersona(value)) {
                    message.reply("해당 페르소나 파일이 없습니다.").queue();
                    return;
                }
            } else if (setting == SessionSetting.MODEL) {
                GeminiModel model = GeminiModel.from(value);
                if (model == null) {
                    message.reply("올바른 모델명이 아닙니다.").queue();
                    return;
                }
                value = model.getId();
            }

            if (db.updateSessionSetting(sessionId, setting, value)) {
                message.addReaction(Emoji.fromUnicode("✅")).queue();
                message.reply(setting.getColumnName() + " 설정이 변경되었습니다. (" + value + ")").queue();
            } else {
                message.reply("설정 변경에 실패하였습니다.").queue();
            }
        } else if (tool != null) {
            if (argSetting.length < 2) {
                boolean isEnabled = db.isToolEnabled(sessionId, tool);
                message.reply("""
                    %s: %s
                    """.formatted(tool.getToolName(), isEnabled)).queue();
                return;
            }

            boolean value = argSetting[1].equals("1") || Boolean.parseBoolean(argSetting[1]);
            if (db.updateToolEnabled(sessionId, tool, value)) {
                message.addReaction(Emoji.fromUnicode("✅")).queue();
                message.reply(tool.getToolName() + " 설정이 변경되었습니다. (" + value + ")").queue();
            } else {
                message.reply("설정 변경에 실패하였습니다.").queue();
            }
        } else {
            message.reply("올바른 설정 항목이 아닙니다.").queue();
            return;
        }
    }
}
