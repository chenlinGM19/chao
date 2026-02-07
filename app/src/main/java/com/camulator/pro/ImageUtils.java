package com.camulator.pro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageUtils {

    public enum FilterType {
        NONE, VIVID, MATTE, B_W, SEPIA, 
        CYBERPUNK, WARM, COOL, VINTAGE, POLAROID,
        KODAK, FUJI_SUPERIA, LEICA_M, DRAMATIC, PASTEL,
        NOIR, SILVER, GOLDEN, TEAL_ORANGE, FADED,
        HDR, CINEMATIC
    }
    
    public static Bitmap imageProxyToBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) return null;
        Matrix matrix = new Matrix();
        matrix.postRotate(image.getImageInfo().getRotationDegrees());
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    public static Bitmap processImage(Bitmap original, FilterType filterType, float saturationVal,
                                      int[] lutRGB, int[] lutR, int[] lutG, int[] lutB,
                                      WatermarkConfig wmConfig, boolean cropToSquare) {
        
        Bitmap workingBitmap = original;
        if (cropToSquare) {
            int w = original.getWidth();
            int h = original.getHeight();
            int size = Math.min(w, h);
            int x = (w - size) / 2;
            int y = (h - size) / 2;
            workingBitmap = Bitmap.createBitmap(original, x, y, size, size);
        }

        Bitmap mutable = workingBitmap.copy(Bitmap.Config.ARGB_8888, true);
        applyPreviewEffects(mutable, filterType, saturationVal, lutRGB, lutR, lutG, lutB);

        if (wmConfig.enabled) {
            if (wmConfig.styleFooter) {
                // Leica Style Footer
                return addFooterWatermark(mutable, wmConfig);
            } else {
                // Overlay
                Canvas c = new Canvas(mutable);
                drawOverlayWatermark(c, mutable.getWidth(), mutable.getHeight(), wmConfig);
            }
        }
        return mutable;
    }
    
    private static Bitmap addFooterWatermark(Bitmap src, WatermarkConfig config) {
        int w = src.getWidth();
        int h = src.getHeight();
        // Dynamic footer size based on image size (approx 10-12%)
        int footerH = (int) (Math.max(w, h) * 0.12f);
        int newH = h + footerH;
        
        Bitmap out = Bitmap.createBitmap(w, newH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(config.backgroundColor);
        c.drawBitmap(src, 0, 0, null);
        
        Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        pText.setColor(config.textColor);
        
        // Left: Model/Lens (simulated) or Custom Text
        float fontSizePrimary = footerH * 0.28f;
        pText.setTextSize(fontSizePrimary);
        pText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        
        String mainText = config.showLogo ? config.customText : "CAMULATOR PRO";
        float padding = w * 0.05f;
        float centerY = h + footerH / 2f + fontSizePrimary / 3f;
        
        c.drawText(mainText, padding, centerY, pText);
        
        // Right: Metadata (Time, Place)
        Paint pMeta = new Paint(Paint.ANTI_ALIAS_FLAG);
        pMeta.setColor(Color.GRAY); // Subtler color
        float fontSizeMeta = footerH * 0.22f;
        pMeta.setTextSize(fontSizeMeta);
        pMeta.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        pMeta.setTextAlign(Paint.Align.RIGHT);
        
        StringBuilder meta = new StringBuilder();
        if (config.showTime) meta.append(new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(new Date()));
        if (config.showPlace && config.placeName != null && !config.placeName.isEmpty()) {
            if (meta.length() > 0) meta.append(" | ");
            meta.append(config.placeName);
        }
        // If too long, trim
        String metaStr = meta.toString();
        
        // Separator line
        Paint pLine = new Paint();
        pLine.setColor(Color.LTGRAY);
        pLine.setStrokeWidth(2f);
        float lineX = w - padding - pMeta.measureText(metaStr) - padding/2;
        c.drawLine(lineX, h + footerH * 0.3f, lineX, h + footerH * 0.7f, pLine);
        
        c.drawText(metaStr, w - padding, centerY, pMeta);
        
        return out;
    }
    
    private static void drawOverlayWatermark(Canvas c, int w, int h, WatermarkConfig config) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(config.textColor);
        p.setShadowLayer(5f, 2f, 2f, Color.parseColor("#99000000"));
        
        float textSize = Math.min(w, h) * 0.035f;
        if (config.textSize == 0) textSize *= 0.8f;
        if (config.textSize == 2) textSize *= 1.4f;
        
        p.setTextSize(textSize);
        
        StringBuilder sb = new StringBuilder();
        if (config.showLogo) sb.append(config.customText);
        if (config.showTime) sb.append("  ").append(new SimpleDateFormat("MM.dd", Locale.US).format(new Date()));
        
        String text = sb.toString();
        float tw = p.measureText(text);
        float padding = w * 0.05f;
        
        float x = padding;
        if (config.position == 1) x = (w - tw) / 2;
        if (config.position == 2) x = w - padding - tw;
        
        c.drawText(text, x, h - padding, p);
    }

    // High performance integer-based processing
    public static void applyPreviewEffects(Bitmap bitmap, FilterType filterType, float saturationVal,
                                           int[] lutRGB, int[] lutR, int[] lutG, int[] lutB) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        float[] colorMatrix = null;
        if (filterType != FilterType.NONE || saturationVal != 0) {
            ColorMatrix cm = getFilterMatrix(filterType);
            if (saturationVal != 0) {
                float satScale = 1.0f + (saturationVal / 100f);
                if (satScale < 0) satScale = 0;
                ColorMatrix satCm = new ColorMatrix();
                satCm.setSaturation(satScale);
                cm.postConcat(satCm);
            }
            colorMatrix = cm.getArray();
        }
        
        boolean hasCurves = isCurveActive(lutRGB) || isCurveActive(lutR) || isCurveActive(lutG) || isCurveActive(lutB);
        if (colorMatrix == null && !hasCurves) return;

        // Optimization: Lift loop invariants
        float m0=0,m1=0,m2=0,m4=0, m5=0,m6=0,m7=0,m9=0, m10=0,m11=0,m12=0,m14=0;
        if (colorMatrix != null) {
            m0=colorMatrix[0]; m1=colorMatrix[1]; m2=colorMatrix[2]; m4=colorMatrix[4];
            m5=colorMatrix[5]; m6=colorMatrix[6]; m7=colorMatrix[7]; m9=colorMatrix[9];
            m10=colorMatrix[10]; m11=colorMatrix[11]; m12=colorMatrix[12]; m14=colorMatrix[14];
        }

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int a = c & 0xFF000000;

            if (colorMatrix != null) {
                float nr = r * m0 + g * m1 + b * m2 + m4;
                float ng = r * m5 + g * m6 + b * m7 + m9;
                float nb = r * m10 + g * m11 + b * m12 + m14;
                r = (nr > 255) ? 255 : (nr < 0) ? 0 : (int) nr;
                g = (ng > 255) ? 255 : (ng < 0) ? 0 : (int) ng;
                b = (nb > 255) ? 255 : (nb < 0) ? 0 : (int) nb;
            }

            if (hasCurves) {
                if (lutRGB != null) { r = lutRGB[r]; g = lutRGB[g]; b = lutRGB[b]; }
                if (lutR != null) r = lutR[r];
                if (lutG != null) g = lutG[g];
                if (lutB != null) b = lutB[b];
            }
            pixels[i] = a | (r << 16) | (g << 8) | b;
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }
    
    private static boolean isCurveActive(int[] lut) {
        return lut != null && Math.abs(lut[128] - 128) > 2;
    }

    private static ColorMatrix getFilterMatrix(FilterType type) {
        ColorMatrix cm = new ColorMatrix();
        switch (type) {
            case VIVID: cm.setSaturation(1.4f); break;
            case MATTE: 
                cm.set(new float[] { 1,0,0,0,0, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,1,0 });
                cm.setSaturation(0.85f);
                break;
            case B_W: cm.setSaturation(0); break;
            case SEPIA:
                cm.set(new float[] { 0.393f, 0.769f, 0.189f, 0, 0, 0.349f, 0.686f, 0.168f, 0, 0, 0.272f, 0.534f, 0.131f, 0, 0, 0, 0, 0, 1, 0 });
                break;
            case CYBERPUNK:
                cm.set(new float[] { 1.2f,0,0,0,0, 0,0.9f,0,0,0, 0,0,1.3f,0,0, 0,0,0,1,0 });
                break;
            case WARM: cm.setScale(1.1f, 1.05f, 0.9f, 1); break;
            case COOL: cm.setScale(0.9f, 1.0f, 1.15f, 1); break;
            case POLAROID:
                 cm.set(new float[] { 1.1f,0,0,0,0, 0,1.05f,0,0,0, 0,0,0.9f,0,0, 0,0,0,1,0 });
                break;
            case LEICA_M:
                 cm.setSaturation(0);
                 ColorMatrix c = new ColorMatrix();
                 c.set(new float[] { 1.3f,0,0,0,-20, 0,1.3f,0,0,-20, 0,0,1.3f,0,-20, 0,0,0,1,0 });
                 cm.postConcat(c);
                break;
            case FUJI_SUPERIA:
                 cm.set(new float[] { 1.05f, -0.05f, 0, 0, 0, 0, 1.05f, 0, 0, 0, 0, 0, 1.1f, 0, 0, 0, 0, 0, 1, 0 });
                break;
            case TEAL_ORANGE:
                cm.set(new float[] { 1.1f,0,0,0,0, 0,1.0f,0,0,0, 0,0,0.8f,0,0, 0,0,0,1,0 });
                break;
            default: break;
        }
        return cm;
    }
    
    // Config classes remain same, implements Cloneable for thread safety
    public static class WatermarkConfig implements Cloneable {
        public boolean enabled = true;
        public boolean styleFooter = true;
        public int backgroundColor = Color.WHITE;
        public int textColor = Color.BLACK;
        public boolean showLogo = true;
        public String customText = "CAMULATOR";
        public boolean showTime = true;
        public boolean showCoords = false;
        public boolean showPlace = true;
        public String latLng = "";
        public String placeName = "";
        public int textSize = 1; 
        public int position = 0; 
        
        @Override
        public WatermarkConfig clone() {
            try { return (WatermarkConfig) super.clone(); } catch (CloneNotSupportedException e) { return new WatermarkConfig(); }
        }
    }

    public static class CurvePreset {
        public String name = "New Preset";
        public List<PointF> rgb = new ArrayList<>();
        public List<PointF> r = new ArrayList<>();
        public List<PointF> g = new ArrayList<>();
        public List<PointF> b = new ArrayList<>();
        public float saturation = 0f;
        public CurvePreset() { reset(); }
        public void reset() {
            rgb = defaultPoints(); r = defaultPoints(); g = defaultPoints(); b = defaultPoints(); saturation = 0f;
        }
        private List<PointF> defaultPoints() { List<PointF> p = new ArrayList<>(); p.add(new PointF(0f, 1f)); p.add(new PointF(1f, 0f)); return p; }
        
        public static CurvePreset fromXmp(String xmpContent) {
            CurvePreset preset = new CurvePreset();
            try {
                Matcher nameMatcher = Pattern.compile("<crs:Name>\\s*<rdf:Alt>\\s*<rdf:li[^>]*>(.*?)</rdf:li>", Pattern.DOTALL).matcher(xmpContent);
                if (nameMatcher.find()) preset.name = nameMatcher.group(1).trim();
                Matcher satMatcher = Pattern.compile("crs:Saturation=\"([^\"]+)\"").matcher(xmpContent);
                if (satMatcher.find()) { preset.saturation = Float.parseFloat(satMatcher.group(1)); }
                preset.rgb = parseXmpPoints(xmpContent, "ToneCurvePV2012");
                preset.r = parseXmpPoints(xmpContent, "ToneCurvePV2012Red");
                preset.g = parseXmpPoints(xmpContent, "ToneCurvePV2012Green");
                preset.b = parseXmpPoints(xmpContent, "ToneCurvePV2012Blue");
            } catch (Exception e) {}
            return preset;
        }
        private static List<PointF> parseXmpPoints(String content, String tagName) {
             List<PointF> points = new ArrayList<>();
             try {
                 Pattern tagPattern = Pattern.compile("<crs:" + tagName + ">(.*?)</crs:" + tagName + ">", Pattern.DOTALL);
                 Matcher tagMatcher = tagPattern.matcher(content);
                 if (tagMatcher.find()) {
                     String inner = tagMatcher.group(1);
                     Pattern liPattern = Pattern.compile("<rdf:li>\\s*(\\d+),\\s*(\\d+)\\s*</rdf:li>");
                     Matcher liMatcher = liPattern.matcher(inner);
                     while (liMatcher.find()) {
                         float x = Float.parseFloat(liMatcher.group(1)) / 255f;
                         float y = Float.parseFloat(liMatcher.group(2)) / 255f;
                         points.add(new PointF(x, 1.0f - y)); 
                     }
                 }
             } catch (Exception e) {}
             if (points.isEmpty()) { points.add(new PointF(0f, 1f)); points.add(new PointF(1f, 0f)); }
             return points;
        }
        public String toXmp() {
            StringBuilder sb = new StringBuilder();
            sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n<rdf:Description rdf:about=\"\" xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\" crs:Version=\"18.1\" crs:Saturation=\"").append((int)saturation).append("\" crs:HasSettings=\"True\">\n<crs:Name>\n<rdf:Alt>\n<rdf:li xml:lang=\"x-default\">").append(name).append("</rdf:li>\n</rdf:Alt>\n</crs:Name>\n");
            appendCurveXmp(sb, "ToneCurvePV2012", rgb);
            appendCurveXmp(sb, "ToneCurvePV2012Red", r);
            appendCurveXmp(sb, "ToneCurvePV2012Green", g);
            appendCurveXmp(sb, "ToneCurvePV2012Blue", b);
            sb.append("</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>");
            return sb.toString();
        }
        private void appendCurveXmp(StringBuilder sb, String tagName, List<PointF> points) {
            sb.append("<crs:").append(tagName).append(">\n<rdf:Seq>\n");
            for(PointF p : points) {
                int x = Math.round(p.x * 255); int y = Math.round((1.0f - p.y) * 255);
                sb.append("<rdf:li>").append(x).append(", ").append(y).append("</rdf:li>\n");
            }
            sb.append("</rdf:Seq>\n</crs:").append(tagName).append(">\n");
        }
    }
}