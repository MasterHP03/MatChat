package org.mat.tool;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.mat.def.SessionSetting;
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
        try (Connection conn = DriverManager.getConnection(DB_URL);
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
                        timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (session_id) REFERENCES sessions(session_id)
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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
                SELECT role, content
                FROM messages
                WHERE session_id = ?
                ORDER BY internal_id ASC
                """;

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String role = rs.getString("role");
                String msg = rs.getString("content");

                Content content = Content.builder()
                        .role(role)
                        .parts(Part.fromText(msg))
                        .build();
                history.add(content);
            }
        } catch (SQLException e) {
            printStackTrace(e);
        }

        return history;
    }

    public long createSession(long sessionId, String userId, String title) {
        // TODO 유저별 관리 메소드 만들면 바꾸기
        String currentPersona = Config.getDefaultPersona();
        String currentModel = Config.getDefaultModel().getId();

        String sql = """
                INSERT INTO sessions
                (session_id, user_id, title, persona, model)
                VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.setString(2, userId);
            pstmt.setString(3, title);
            pstmt.setString(4, currentPersona);
            pstmt.setString(5, currentModel);
            pstmt.executeUpdate();

            logger.info("세션 생성 완료: {}", sessionId);
        } catch (SQLException e) {
            printStackTrace(e);
        }
        return sessionId;
    }

    public boolean addMessage(long sessionId, long msgId, String role, String content) {
        return addMessage(sessionId, msgId, role, content, null, 0, 0, 0, 0, 0, 0);
    }

    public boolean addMessage(long sessionId, long msgId, String role, String content, String modelId,
                              int tokenInput, int tokenCache, int tokenTool, int tokenThought, int tokenOutput, int tokenTotal) {
        String sql = """
                INSERT INTO messages
                (session_id, msg_id, role, content, model_id,
                token_input, token_cache, token_tool, token_thought, token_output, token_total)
                VALUES (?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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
        createSession(newSessionId, userId, newTitle);

        String sql = """
                INSERT INTO messages
                (msg_id, session_id, role, content, timestamp)
                SELECT msg_id, ?, role, content, timestamp
                FROM messages
                WHERE session_id = ?
                """;

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

        try (Connection conn = DriverManager.getConnection(DB_URL);
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

    public void printStackTrace(SQLException e) {
        logger.error("DB 처리 중 오류 발생: ", e);
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
