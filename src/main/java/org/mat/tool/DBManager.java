package org.mat.tool;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.mat.def.SessionSetting;
import org.mat.def.Tools;
import org.mat.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manager of DB which stores all the sessions and messages.
 */
public class DBManager {
    private final String DB_URL = Config.getDbUrl();
    private static final Logger logger = LoggerFactory.getLogger(DBManager.class);

    public void init() {
        // 연결 시도
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            logger.info("DB 연결 완료");

            // 테이블 만들기
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        session_id INTEGER PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        title TEXT,
                        persona TEXT,
                        model TEXT,
                        user_note TEXT DEFAULT '',
                        use_generate_image INTEGER DEFAULT 0,
                        use_search INTEGER DEFAULT 0,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    );
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        internal_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        msg_id INTEGER NOT NULL,
                        session_id INTEGER,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        model_id TEXT,
                        token_input INTEGER DEFAULT 0,
                        token_cache INTEGER DEFAULT 0,
                        token_tool INTEGER DEFAULT 0,
                        token_thought INTEGER DEFAULT 0,
                        token_output INTEGER DEFAULT 0,
                        token_total INTEGER DEFAULT 0,
                        image_content TEXT,
                        reference_image_ids TEXT,
                        image_token_input INTEGER DEFAULT 0,
                        image_token_cache INTEGER DEFAULT 0,
                        image_token_tool INTEGER DEFAULT 0,
                        image_token_thought INTEGER DEFAULT 0,
                        image_token_output INTEGER DEFAULT 0,
                        image_token_total INTEGER DEFAULT 0,
                        search_sources TEXT,
                        timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (session_id)
                        REFERENCES sessions(session_id),
                        UNIQUE(session_id, msg_id)
                    );
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS attachments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id INTEGER NOT NULL,
                        msg_id INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        url TEXT NOT NULL,
                        gemini_uri TEXT,
                        archive_msg_id INTEGER NOT NULL,
                        user_order INTEGER DEFAULT 0,
                        uri_created_at DATETIME,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (session_id, msg_id)
                        REFERENCES messages(session_id, msg_id) ON DELETE CASCADE
                    );
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS api_usage (
                        record_date TEXT,
                        model_id TEXT,
                        google_search_count INTEGER DEFAULT 0,
                        brave_search_count INTEGER DEFAULT 0,
                        PRIMARY KEY (record_date, model_id)
                    );
                    """);

            logger.info("DB 생성됨");
        } catch (SQLException e) {
            printStackTrace(e);
        }
    }

    public String getFullHistory(long sessionId) {
        StringBuilder history = new StringBuilder();
        String sql = """
                SELECT role, content
                FROM messages
                WHERE session_id = ?
                ORDER BY internal_id ASC
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String role = rs.getString("role");
                String msg = rs.getString("content");

                if (role.equalsIgnoreCase("user")) {
                    history.append("<User>\n").append(msg).append("\n");
                } else {
                    history.append("<Bot>\n").append(msg).append("\n");
                }
            }
        } catch (SQLException e) {
            printStackTrace(e);
        }

        return history.toString();
    }

    /**
     * Returns structured history to toss it to Gemini directly.
     * @param sessionId Chat session to get full history.
     * @return List of the contents.
     */
    public List<Content> getStructuredHistory(long sessionId) {
        List<Content> history = new ArrayList<>();
        String sql = """
                SELECT msg_id, role, content
                FROM messages
                WHERE session_id = ?
                ORDER BY internal_id ASC
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                long msgId = rs.getLong("msg_id");
                String role = rs.getString("role");
                String msg = rs.getString("content");

                List<Part> parts = new ArrayList<>();

                // 메시지가 존재할 경우 (메시지는 없고 이미지만 있을 수도 있음)
                if (msg != null && !msg.isBlank()) {
                    parts.add(Part.fromText(msg));
                }

                // 이미지 추가
                List<FileUtil.AttachmentInfo> attachments = getAttachments(sessionId, msgId);
                for (FileUtil.AttachmentInfo att : attachments) {
                    parts.add(Part.fromText(FileUtil.fileTagFormat.formatted(
                            att.archiveMsgId(), att.geminiUri(), att.url(), att.mimeType())));
                }

                Content content = Content.builder()
                        .role(role)
                        .parts(parts)
                        .build();
                history.add(content);
            }
        } catch (SQLException e) {
            printStackTrace(e);
        }

        return history;
    }

    public long createSession(long sessionId, String userId, String title) {
        String currentPersona = Config.getDefaultPersona();
        String currentModel = Config.getDefaultModel().getId();

        return createSession(sessionId, userId, title, currentPersona, currentModel, "");
    }

    public long createSession(long sessionId, String userId, String title, String persona, String model, String userNote) {
        String sql = """
                INSERT INTO sessions
                (session_id, user_id, title, persona, model, user_note)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setString(2, userId);
            pstmt.setString(3, title);
            pstmt.setString(4, persona);
            pstmt.setString(5, model);
            pstmt.setString(6, userNote);
            pstmt.executeUpdate();

            logger.info("세션 생성 완료: {}", sessionId);
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return sessionId;
    }

    public boolean addMessage(long sessionId, long msgId, String role, String content,
                              String searchSources) {
        return addMessage(sessionId, msgId, role, content, null,
                0, 0, 0, 0, 0, 0,
                searchSources);
    }

    public boolean addMessage(long sessionId, long msgId, String role, String content, String modelId,
                              int tokenInput, int tokenCache, int tokenTool, int tokenThought, int tokenOutput, int tokenTotal,
                              String searchSources) {
        return addMessage(sessionId, msgId, role, content,
                modelId, tokenInput, tokenCache, tokenTool, tokenThought, tokenOutput, tokenTotal,
                null, new ArrayList<>(), 0, 0, 0, 0, 0, 0,
                searchSources);
    }

    public boolean addMessage(long sessionId, long msgId, String role, String content, String modelId,
                              int tokenInput, int tokenCache, int tokenTool, int tokenThought, int tokenOutput, int tokenTotal,
                              String imagePrompt, List<String> refIds, int imageTokenInput, int imageTokenCache, int imageTokenTool,
                              int imageTokenThought, int imageTokenOutput, int imageTokenTotal,
                              String searchSources) {
        String sql = """
                INSERT INTO messages
                (session_id, msg_id, role, content, model_id,
                token_input, token_cache, token_tool, token_thought, token_output, token_total,
                image_content, reference_image_ids, image_token_input, image_token_cache, image_token_tool,
                image_token_thought, image_token_output, image_token_total,
                search_sources)
                VALUES (?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?)
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, msgId);
            pstmt.setString(3, role);
            pstmt.setString(4, content);
            pstmt.setString(5, modelId);
            pstmt.setInt(6, tokenInput);
            pstmt.setInt(7, tokenCache);
            pstmt.setInt(8, tokenTool);
            pstmt.setInt(9, tokenThought);
            pstmt.setInt(10, tokenOutput);
            pstmt.setInt(11, tokenTotal);
            pstmt.setString(12, imagePrompt);
            pstmt.setString(13, String.join(",", refIds));
            pstmt.setInt(14, imageTokenInput);
            pstmt.setInt(15, imageTokenCache);
            pstmt.setInt(16, imageTokenTool);
            pstmt.setInt(17, imageTokenThought);
            pstmt.setInt(18, imageTokenOutput);
            pstmt.setInt(19, imageTokenTotal);
            pstmt.setString(20, searchSources);
            pstmt.executeUpdate();

            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public int editMessage(long sessionId, long msgId, String newContent) {
        String sql = """
                UPDATE messages
                SET content = ?
                WHERE session_id = ? AND msg_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newContent);
            pstmt.setLong(2, sessionId);
            pstmt.setLong(3, msgId);
            int rows = pstmt.executeUpdate();

            if (rows > 0) logger.info("메시지 수정됨 ({})", msgId);
            return rows;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return -1;
    }

    public boolean deleteMessage(long sessionId, long msgId) {
        String sql = """
                DELETE FROM messages
                WHERE session_id = ? AND msg_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, msgId);
            pstmt.executeUpdate();

            logger.info("메시지 삭제됨 ({})", msgId);
            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public int rewindSession(long sessionId, long targetMsgId) {
        String sql = """
                DELETE FROM messages
                WHERE session_id = ? AND msg_id > ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, targetMsgId);
            int rows = pstmt.executeUpdate();

            logger.info("되감기 (세션 {}에서 {}개 삭제)", sessionId, rows);
            return rows;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return -1;
    }

    public boolean forkSession(long oldSessionId, long newSessionId, String userId, String newTitle) {
        SessionInfo info = getSessionInfo(oldSessionId);

        createSession(newSessionId, userId, newTitle, info.persona(), info.model(), info.userNote());

        for (Tools tool : Tools.values()) {
            if (isToolEnabled(oldSessionId, tool)) {
                updateToolEnabled(newSessionId, tool, true);
            }
        }

        String sql = """
                INSERT INTO messages
                (msg_id, session_id, role, content, timestamp)
                SELECT msg_id, ?, role, content, timestamp
                FROM messages
                WHERE session_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, newSessionId);
            pstmt.setLong(2, oldSessionId);
            int rows = pstmt.executeUpdate();

            logger.info("포크 ({}개 복제)", rows);
            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public boolean isMessageExists(long sessionId, long msgId) {
        String sql = """
                SELECT 1
                FROM messages
                WHERE session_id = ? AND msg_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, msgId);
            return pstmt.executeQuery().next();
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public boolean isSessionExists(long sessionId) {
        String sql = """
                SELECT 1
                FROM sessions
                WHERE session_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            return pstmt.executeQuery().next();
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public String getMessage(long sessionId, long msgId) {
        String sql = """
                SELECT content
                FROM messages
                WHERE session_id = ? AND msg_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, msgId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("content");
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return null;
    }

    public SessionInfo getSessionInfo(long sessionId) {
        String sql = """
                SELECT persona, model, user_note
                FROM sessions
                WHERE session_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new SessionInfo(
                        rs.getString("persona"),
                        rs.getString("model"),
                        rs.getString("user_note")
                );
            }
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return new SessionInfo(Config.getDefaultPersona(),
                Config.getDefaultModel().getId(),
                "");
    }

    public boolean updateSessionSetting(long sessionId, SessionSetting setting, String value) {
        String sql = """
                UPDATE sessions
                SET %s = ?
                WHERE session_id = ?
                """.formatted(setting.getColumnName());

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, value);
            pstmt.setLong(2, sessionId);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public MessageInfo getLastMessageInfo(long sessionId) {
        String sql = """
                SELECT msg_id, role
                FROM messages
                WHERE session_id = ?
                ORDER BY internal_id DESC LIMIT 1
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new MessageInfo(
                        rs.getLong("msg_id"),
                        rs.getString("role")
                );
            }
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return null; // 메시지 없음
    }

    public boolean addAttachment(long sessionId, long msgId, String type, String url, long archiveMsgId,
                                 String mimeType, int userOrder) {
        String sql = """
                INSERT INTO attachments
                (session_id, msg_id, type, mime_type, url, archive_msg_id, user_order)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, msgId);
            pstmt.setString(3, type);
            pstmt.setString(4, mimeType);
            pstmt.setString(5, url);
            pstmt.setLong(6, archiveMsgId);
            pstmt.setInt(7, userOrder);
            pstmt.executeUpdate();

            logger.info("첨부파일 링크 저장 완료 ({})", url);
            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public List<FileUtil.AttachmentInfo> getAttachments(long sessionId, long msgId) {
        List<FileUtil.AttachmentInfo> atts = new ArrayList<>();
        String sql = """
                SELECT url, archive_msg_id, mime_type,
                    CASE
                        WHEN uri_created_at IS NOT NULL AND uri_created_at >= datetime('now', '-46 hours')
                        THEN gemini_uri
                        ELSE NULL
                    END AS gemini_uri
                FROM attachments
                WHERE session_id = ? AND msg_id = ?
                ORDER BY user_order ASC, id ASC
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, msgId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                atts.add(new FileUtil.AttachmentInfo(
                        rs.getString("url"),
                        rs.getLong("archive_msg_id"),
                        rs.getString("gemini_uri"),
                        rs.getString("mime_type")));
            }
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return atts;
    }

    public boolean updateFileUrl(long archiveMsgId, String url) {
        String sql = """
                UPDATE attachments
                SET url = ?
                WHERE archive_msg_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, url);
            pstmt.setLong(2, archiveMsgId);
            pstmt.executeUpdate();

            logger.info("첨부파일 링크 업데이트됨 ({})", url);
            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public boolean updateGeminiUri(long archiveMsgId, String geminiUri) {
        String sql = """
                UPDATE attachments
                SET gemini_uri = ?, uri_created_at = CURRENT_TIMESTAMP
                WHERE archive_msg_id = ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, geminiUri);
            pstmt.setLong(2, archiveMsgId);
            pstmt.executeUpdate();

            logger.info("Files API 캐시 업데이트됨 ({})", geminiUri);
            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    /**
     * Search for Google Search usage count.
     * @param datePattern "2026-03-13" (Date-wise) or "2026-03-%" (Month-wise)
     * @param modelPattern "gemini-2.5-pro" (Individual) or "gemini-3%" (Shared)
     * @return Google Search usage count of the model(s) in the given period.
     */
    public int getGoogleSearchCount(String datePattern, String modelPattern) {
        String sql = """
                SELECT SUM(google_search_count)
                FROM api_usage
                WHERE record_date LIKE ? AND model_id LIKE ?
                """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, datePattern);
            pstmt.setString(2, modelPattern);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return 0;
    }

    /**
     * Update Google Search usage data.
     * @param date "2026-03-13" (Date)
     * @param modelId "gemini-3.1-pro" (ModelID)
     * @param count Total count of Google Search usage this turn.
     * @return Boolean that indicates if the operation was successful.
     */
    public boolean addGoogleSearchCount(String date, String modelId, int count) {
        String sql = """
                INSERT INTO api_usage (record_date, model_id, google_search_count)
                VALUES (?, ?, ?)
                ON CONFLICT(record_date, model_id) DO
                UPDATE SET google_search_count = google_search_count + ?;
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, date);
            pstmt.setString(2, modelId);
            pstmt.setInt(3, count);
            pstmt.setInt(4, count);
            pstmt.executeUpdate();

            logger.info("구글 검색 카운트 {} 증가 ({} / {})", count, date, modelId);
            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public boolean isToolEnabled(long sessionId, Tools tool) {
        String columnName = "use_" + tool.getToolName();
        String sql = """
                SELECT %s
                FROM sessions
                WHERE session_id = ?
                """.formatted(columnName);

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getInt(columnName) == 1;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    public boolean updateToolEnabled(long sessionId, Tools tool, boolean value) {
        String sql = """
                UPDATE sessions
                SET %s = ?
                WHERE session_id = ?
                """.formatted("use_" + tool.getToolName());

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, value ? 1 : 0);
            pstmt.setLong(2, sessionId);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return false;
    }

    private void printStackTrace(SQLException e) {
        logger.error("DB 처리 중 오류 발생: ", e);
    }

    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(DB_URL);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }

        return conn;
    }

    public record SessionInfo(String persona, String model, String userNote) {
        public String get(SessionSetting info) {
            return switch (info) {
                case SessionSetting.PERSONA -> persona;
                case SessionSetting.MODEL -> model;
                case SessionSetting.NOTE -> userNote;
            };
        }
    }

    public record MessageInfo(long msgId, String role) {}
}
