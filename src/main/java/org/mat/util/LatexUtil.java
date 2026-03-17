package org.mat.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class LatexUtil {

    private static final Logger logger = LoggerFactory.getLogger(LatexUtil.class);
    private static final HttpClient client = HttpClient.newHttpClient();

    public static String renderToImageUrl(String formula) {
        try {
            formula = formula.replace("\\frac", "\\dfrac");

            String preamble = "\\usepackage{amsmath}\\usepackage{amsfonts}\\usepackage{amssymb}";
            String body = "formula=" + URLEncoder.encode(formula, StandardCharsets.UTF_8).replace("+", "%20") +
                    "&fsize=20" +
                    "&fcolor=ffffff" +
                    "&mode=0" +
                    "&out=1" +
                    "&preamble=" + URLEncoder.encode(preamble, StandardCharsets.UTF_8).replace("+", "%20");

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://quicklatex.com/latex3.f"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String[] lines = response.body().split("\n");

            // QuickLaTeX: 첫 줄 0이면 성공, 두 번째 줄 첫 번째 arg에 이미지 URL
            if (lines.length > 1 && lines[0].trim().equals("0")) {
                return lines[1].trim().split("\\s+")[0];
            } else {
                logger.error("QuickLaTeX 렌더링 실패: {}", response.body());
                return null;
            }
        } catch (Exception e) {
            logger.error("QuickLaTeX API 통신 에러", e);
            return null;
        }
    }

}
