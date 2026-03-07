package org.mat.event;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.mat.def.ImageType;
import org.mat.tool.ChatService;
import org.mat.tool.CommandManager;
import org.mat.tool.Config;
import org.mat.tool.DBManager;
import org.mat.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives message events and delivers it to proper class.
 */
public class MessageEvent extends ListenerAdapter {

    private static final String PREFIX = Config.getPrefix();
    private static final Logger logger = LoggerFactory.getLogger(MessageEvent.class);

    private final DBManager db;
    private final CommandManager cmd;
    private final ChatService chat;

    private final Map<Long, Long> cooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN = Config.getCooldown();

    public MessageEvent() {
        db = new DBManager();
        db.init();

        chat = new ChatService(db);
        cmd = new CommandManager(db, chat);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        User user = event.getAuthor();
        if (user.isBot()) return;

        Message message = event.getMessage();
        String msg = message.getContentRaw();

        boolean hasValidAtt = message.getAttachments().stream().anyMatch(Message.Attachment::isImage);
        if (msg.isBlank() && !hasValidAtt) return;

        long userId = user.getIdLong();
        long now = System.currentTimeMillis();

        // 채팅 쿨타임
        if (cooldowns.containsKey(userId)) {
            long lastUsed = cooldowns.get(userId);
            if (now - lastUsed < COOLDOWN) {
                message.addReaction(Emoji.fromUnicode("⏳")).queue();
                return;
            }
        }
        cooldowns.put(userId, now);

        // Prefix -> 명령어, CommandManager
        if (msg.startsWith(PREFIX)) {
            cmd.handle(event);
            return;
        }

        // No Prefix -> Gemini 대화, ChatService (스레드만)
        if (event.getChannelType().isThread()) {
            ThreadChannel thread = event.getChannel().asThreadChannel();
            long sessionId = thread.getIdLong();
            if (!db.isSessionExists(sessionId)) return;

            db.addMessage(sessionId,message.getIdLong(), "user", message.getContentRaw());

            List<Message.Attachment> attachments = message.getAttachments();
            List<CompletableFuture<Void>> imageFutures = new ArrayList<>();
            for (Message.Attachment img : attachments) {
                if (img.isImage()) {
                    // 다운로드 -> 업로드 -> DB 저장
                    CompletableFuture<Void> future = img.getProxy().download().thenCompose(inputStream -> {
                        try (var is = inputStream) {
                            byte[] bytes = is.readAllBytes();
                            return FileUtil.upload(event.getJDA(), bytes, img.getFileName(), sessionId);
                        } catch (Exception e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    }).thenAccept(result -> {
                        if (result != null) {
                            db.addAttachment(sessionId,
                                    message.getIdLong(),
                                    ImageType.INPUT.name(),
                                    result.url(),
                                    result.archiveMsgId());
                        }
                    });
                    imageFutures.add(future);
                }
            }

            if (!imageFutures.isEmpty()) {
                thread.sendTyping().queue();

                // 모든 이미지 저장이 완료되면 채팅 처리
                CompletableFuture.allOf(imageFutures.toArray(CompletableFuture[]::new))
                        .thenRunAsync(() -> chat.processUserMessage(sessionId, message))
                        .exceptionally(e -> {
                            logger.error("이미지 다중 처리 중 에러 발생", e);
                            return null;
                        });
            } else {
                CompletableFuture.runAsync(() -> chat.processUserMessage(sessionId, message));
            }
        }
    }

}
