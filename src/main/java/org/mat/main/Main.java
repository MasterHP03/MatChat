package org.mat.main;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.mat.event.MessageEvent;
import org.mat.tool.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * Main class. Starts and terminates the bot.
 */
public class Main {

    private static JDA jda;
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String token = Config.getDiscordToken();
        JDABuilder jb = JDABuilder.createDefault(token);
        jb.setAutoReconnect(true);
        jb.enableIntents(GatewayIntent.MESSAGE_CONTENT);
        // jb.enableIntents(GatewayIntent.GUILD_MEMBERS);
        // jb.enableIntents(GatewayIntent.GUILD_PRESENCES);
        jb.setStatus(OnlineStatus.ONLINE);

        MessageEvent comm = new MessageEvent();
        jb.addEventListeners(comm);

        try {
            jda = jb.build();
        } catch (Exception e) {
            logger.error("JDA 빌드 중 오류 발생", e);
            return;
        }

        logger.info("프로그램 시작");

        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine();
        while (s != null) {
            if (s.equalsIgnoreCase("stop")) {
                logger.info("프로그램 종료 시작...");
                try {
                    jda.shutdown();
                } catch (Exception e) {
                    logger.error("JDA 종료 중 오류 발생", e);
                }

                logger.info("프로그램 종료");
                break;
            }
            s = sc.nextLine();
        }
        sc.close();
    }

}
