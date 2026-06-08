package org.chibot;

import org.chibot.Config.ChiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class ChiMain {

    private static final Logger log = LoggerFactory.getLogger(ChiMain.class);
    private static final String ESC = String.valueOf((char) 27);

    public static void main(String[] args) {
        forceUtf8Console();
        printBanner();

        ChiConfig config;
        try {
            config = ChiConfig.load();
        } catch (IOException e) {
            log.error("Falha ao ler/criar ChiConfig.json", e);
            return;
        }

        if (!config.isTokenPresent()) {
            log.warn("Token nao configurado em ChiConfig.json. Preencha o token e reinicie o bot.");
            return;
        }

        log.info("Configuracao carregada. Prefixo: {}", config.getPrefix());

        try {
            new ChiBot(config).start();
        } catch (InterruptedException e) {
            log.error("Falha ao iniciar o ChiBot", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Garante que o console escreva em UTF-8. No Windows o padrao costuma ser
     * CP1252, que vira aqueles caracteres tortos (♡ -> ?) nas mensagens fofas.
     */
    private static void forceUtf8Console() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private static void printBanner() {
        try (InputStream in = ChiMain.class.getResourceAsStream("/banner.txt")) {
            if (in != null) {
                String banner = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                System.out.println(gradient(banner));
            }
        } catch (IOException e) {
            // banner e so enfeite; se falhar, segue sem ele
        }
    }

    /**
     * Pinta o banner com um degrade fofo rosa -> lavanda -> ciano, uma cor por
     * linha. Usa truecolor (24-bit); terminais que nao suportam simplesmente
     * ignoram os codigos. O reset garante que nada vaze pra linha seguinte.
     */
    private static String gradient(String banner) {
        // (r, g, b) das tres paradas do degrade
        int[] top = {255, 170, 205};   // rosa
        int[] mid = {190, 165, 255};   // lavanda
        int[] bot = {155, 225, 240};   // ciano

        String[] lines = banner.split("\n", -1);
        StringBuilder sb = new StringBuilder(banner.length() + lines.length * 24);
        for (int i = 0; i < lines.length; i++) {
            double t = lines.length > 1 ? (double) i / (lines.length - 1) : 0;
            int[] from = t < 0.5 ? top : mid;
            int[] to   = t < 0.5 ? mid : bot;
            double k = t < 0.5 ? t * 2 : (t - 0.5) * 2;
            int r = (int) Math.round(from[0] + (to[0] - from[0]) * k);
            int g = (int) Math.round(from[1] + (to[1] - from[1]) * k);
            int b = (int) Math.round(from[2] + (to[2] - from[2]) * k);
            sb.append(ESC).append("[38;2;").append(r).append(';').append(g).append(';').append(b).append('m')
                    .append(lines[i]).append(ESC).append("[0m");
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}