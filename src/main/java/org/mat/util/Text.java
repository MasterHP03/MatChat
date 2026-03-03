package org.mat.util;

public class Text {

    public static void println(String msg) {
        System.out.println(format(msg + "&r"));
    }

    public static void warnln(String msg) {
        System.out.println(format("&#<255;255;0>" + msg + "&r"));
    }

    public static void errln(String msg) {
        System.out.println(format("&#<255;0;0>" + msg + "&r"));
    }

    public static void print(String msg) {
        System.out.print(format(msg));
    }

    private static String format(String msg) {
        String res;
        res = msg.replaceAll("&r", "\033[0m");
        res = res.replaceAll("&l", "\033[1m");
        res = res.replaceAll("&o", "\033[3m");
        res = res.replaceAll("&n", "\033[4m");
        res = res.replaceAll("&m", "\033[9m");
        res = res.replaceAll("&#<(\\d+;\\d+;\\d+)>", "\033[38;2;$1m");
        res = res.replaceAll("&#b<(\\d+;\\d+;\\d+)>", "\033[48;2;$1m");
        return res;
    }

}
