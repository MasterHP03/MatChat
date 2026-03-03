package org.mat.util;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.mat.tool.Config;
import org.mat.tool.DBManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public class FileUtil {

    private static final Logger logger = LoggerFactory.getLogger(DBManager.class);

    /**
     * Uploads uploaded or generated images to the archive channel.
     * @param jda JDA instance.
     * @param imageBytes Image to upload.
     * @param fileName Filename of the image.
     * @param sessionId ID of the current session.
     */
    public static CompletableFuture<AttachmentInfo> upload(JDA jda, byte[] imageBytes, String fileName, long sessionId) {
        CompletableFuture<AttachmentInfo> future = new CompletableFuture<>();
        TextChannel archive = jda.getTextChannelById(Config.getArchive());

        if (archive != null) {
            archive.sendFiles(FileUpload.fromData(imageBytes, fileName))
                    .setContent("세션 " + sessionId + "의 이미지")
                    .queue(message -> {
                        String imageUrl = message.getAttachments().getFirst().getUrl();
                        long archiveMsgId = message.getIdLong();
                        future.complete(new AttachmentInfo(imageUrl, archiveMsgId));
                    }, future::completeExceptionally);
        } else {
            future.completeExceptionally(new RuntimeException("아카이브에 파일 업로드 중 에러 (아카이브 채널 ID 실수?)"));
        }

        return future;
    }

    public record AttachmentInfo(String url, long archiveMsgId) {}

}
