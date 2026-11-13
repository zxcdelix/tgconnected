package ru.femboy;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class QrRenderer {
    public static Path render(String text) throws Exception {
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.MARGIN, 0));
        int scale = 10, border = 4;
        int size = (m.getWidth() + border * 2) * scale;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int mx = x / scale - border, my = y / scale - border;
                boolean dark = mx >= 0 && my >= 0 && mx < m.getWidth() && my < m.getHeight() && m.get(mx, my);
                img.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
            }
        }
        Files.createDirectories(TgConfig.dir());
        Path out = TgConfig.dir().resolve("qr.png");
        ImageIO.write(img, "png", out.toFile());
        return out;
    }
}