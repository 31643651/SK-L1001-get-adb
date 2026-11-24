package com.c10h.launcher;

import android.graphics.Bitmap;

/** 轻量级模糊：缩小后做两次 box blur，再放大；不依赖 RenderScript。 */
public final class Blur {

    private Blur() {
    }

    public static Bitmap blur(Bitmap src, int radius) {
        if (src == null) {
            return null;
        }
        try {
            int scale = 4;
            int w = Math.max(1, src.getWidth() / scale);
            int h = Math.max(1, src.getHeight() / scale);
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            int[] pix = new int[w * h];
            small.getPixels(pix, 0, w, 0, 0, w, h);
            int r = Math.max(1, Math.min(radius, 24));
            pix = boxBlur(pix, w, h, r);
            pix = boxBlur(pix, w, h, r);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            out.setPixels(pix, 0, w, 0, 0, w, h);
            return Bitmap.createScaledBitmap(out, src.getWidth(), src.getHeight(), true);
        } catch (Throwable t) {
            return src;
        }
    }

    private static int[] boxBlur(int[] src, int w, int h, int r) {
        int[] tmp = new int[w * h];
        int[] out = new int[w * h];
        // horizontal
        for (int y = 0; y < h; y++) {
            int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int x = -r; x <= r; x++) {
                int xx = Math.max(0, Math.min(w - 1, x));
                int c = src[y * w + xx];
                sumA += (c >>> 24) & 0xFF;
                sumR += (c >> 16) & 0xFF;
                sumG += (c >> 8) & 0xFF;
                sumB += c & 0xFF;
                count++;
            }
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = ((sumA / count) << 24) | ((sumR / count) << 16)
                        | ((sumG / count) << 8) | (sumB / count);
                int xOut = Math.max(0, Math.min(w - 1, x - r));
                int xIn = Math.max(0, Math.min(w - 1, x + r + 1));
                int co = src[y * w + xOut];
                int ci = src[y * w + xIn];
                sumA += ((ci >>> 24) & 0xFF) - ((co >>> 24) & 0xFF);
                sumR += ((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF);
                sumG += ((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF);
                sumB += (ci & 0xFF) - (co & 0xFF);
            }
        }
        // vertical
        for (int x = 0; x < w; x++) {
            int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int y = -r; y <= r; y++) {
                int yy = Math.max(0, Math.min(h - 1, y));
                int c = tmp[yy * w + x];
                sumA += (c >>> 24) & 0xFF;
                sumR += (c >> 16) & 0xFF;
                sumG += (c >> 8) & 0xFF;
                sumB += c & 0xFF;
                count++;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((sumA / count) << 24) | ((sumR / count) << 16)
                        | ((sumG / count) << 8) | (sumB / count);
                int yOut = Math.max(0, Math.min(h - 1, y - r));
                int yIn = Math.max(0, Math.min(h - 1, y + r + 1));
                int co = tmp[yOut * w + x];
                int ci = tmp[yIn * w + x];
                sumA += ((ci >>> 24) & 0xFF) - ((co >>> 24) & 0xFF);
                sumR += ((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF);
                sumG += ((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF);
                sumB += (ci & 0xFF) - (co & 0xFF);
            }
        }
        return out;
    }
}
