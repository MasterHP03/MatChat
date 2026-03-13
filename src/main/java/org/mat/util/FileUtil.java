package org.mat.util;

import com.google.genai.Client;
import com.google.genai.types.File;
import com.google.genai.types.UploadFileConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.mat.tool.Config;
import org.mat.tool.DBManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class FileUtil {

    private static final Logger logger = LoggerFactory.getLogger(FileUtil.class);
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public static final String imageTagFormat = "[IMG|%s|%s|%s]";
    public static final String imageFormat = "[Reference Id: %s]";

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
                        future.complete(new AttachmentInfo(imageUrl, archiveMsgId, null));
                    }, future::completeExceptionally);
        } else {
            future.completeExceptionally(new RuntimeException("아카이브에 파일 업로드 중 에러 (아카이브 채널 ID 실수?)"));
        }

        return future;
    }

    /**
     * Gets data as byte array by HTTP request.
     * @param imageUrl HTTP URL of data.
     * @return Data in byte array.
     */
    private static HttpResponse<byte[]> download(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).build();

            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            logger.error("HTTP 이미지 다운로드 실패 ({})", imageUrl, e);
            return null;
        }
    }

    public static ImageInfo getSafeImageBytes(JDA jda, DBManager db, String oldUrl, long archiveMsgId) {
        HttpResponse<byte[]> response = download(oldUrl);

        try {
            if (response == null || response.statusCode() == 403 || response.statusCode() == 404) {
                logger.warn("HTTP 링크 만료, 디스코드 접근 시도");

                TextChannel archive = jda.getTextChannelById(Config.getArchive());
                if (archive == null) {
                    throw new RuntimeException("유효한 채널이 아님 (ID 실수?)");
                }

                Message msg = archive.retrieveMessageById(archiveMsgId).complete();
                String freshUrl = msg.getAttachments().getFirst().getUrl();

                db.updateImageUrl(archiveMsgId, freshUrl);

                response = download(freshUrl);
                if (response == null || response.statusCode() != 200) {
                    throw new RuntimeException("새로 불러온 링크로도 다운로드 실패");
                }
            }
            byte[] imageBytes = response.body();
            String mimeType = response.headers().firstValue("Content-Type").orElse("image/png");
            return new ImageInfo(imageBytes, mimeType);
        } catch (Exception e) {
            logger.error("디스코드 이미지 다운로드 실패", e);
            return null;
        }
    }

    public static String uploadToGemini(byte[] bytes, String mimeType, String displayName) {
        try (Client client = Client.builder()
                .apiKey(Config.getGeminiKey()).
                build()) {
            File uploadedFile = client.files.upload(
                    bytes,
                    UploadFileConfig.builder()
                            .mimeType(mimeType)
                            .displayName(displayName)
                            .build()
            );
            String uri = uploadedFile.uri().orElseThrow();
            logger.info("Files API 업로드 성공, URI: {}", uri);
            return uri;
        } catch (Exception e) {
            logger.error("Gemini Files API 업로드 실패", e);
            return null;
        }
    }

    public record AttachmentInfo(String url, long archiveMsgId, String geminiUri) {}

    public record ImageInfo(byte[] bytes, String mimeType) {}

}
