package com.camulator.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CurveView extends View {

    public enum Channel { RGB, RED, GREEN, BLUE }
    
    public interface OnCurveChangeListener {
        void onCurveChanged();
    }

    private Channel currentChannel = Channel.RGB;
    private Paint linePaint, gridPaint, pointPaint, borderPaint, fillPaint;
    private OnCurveChangeListener listener;
    
    private List<PointF> pointsRGB, pointsR, pointsG, pointsB;
    private int activePointIndex = -1;

    public CurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public void setOnCurveChangeListener(OnCurveChangeListener listener) {
        this.listener = listener;
    }

    private void init() {
        linePaint = new Paint();
        linePaint.setStrokeWidth(6f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setAntiAlias(true);
        linePaint.setShadowLayer(4f, 0f, 2f, Color.parseColor("#40000000"));

        pointPaint = new Paint();
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);
        pointPaint.setShadowLayer(3f, 0f, 1f, Color.parseColor("#80000000"));

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#33FFFFFF"));
        gridPaint.setStrokeWidth(2f);
        
        borderPaint = new Paint();
        borderPaint.setColor(Color.parseColor("#66FFFFFF"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        
        fillPaint = new Paint();
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        resetCurves();
    }
    
    public void resetCurves() {
        pointsRGB = createDefaultPoints();
        pointsR = createDefaultPoints();
        pointsG = createDefaultPoints();
        pointsB = createDefaultPoints();
        invalidate();
        notifyListener();
    }
    
    private void notifyListener() {
        if (listener != null) listener.onCurveChanged();
    }
    
    private List<PointF> createDefaultPoints() {
        List<PointF> list = new ArrayList<>();
        list.add(new PointF(0f, 1f)); // 0,0 visual
        list.add(new PointF(1f, 0f)); // 1,1 visual
        return list;
    }

    public void setChannel(Channel channel) {
        this.currentChannel = channel;
        invalidate();
    }

    private List<PointF> getCurrentPoints() {
        switch (currentChannel) {
            case RED: return pointsR;
            case GREEN: return pointsG;
            case BLUE: return pointsB;
            default: return pointsRGB;
        }
    }
    
    public List<PointF> getPoints(Channel c) {
        switch (c) {
            case RED: return new ArrayList<>(pointsR);
            case GREEN: return new ArrayList<>(pointsG);
            case BLUE: return new ArrayList<>(pointsB);
            default: return new ArrayList<>(pointsRGB);
        }
    }
    
    public void setPoints(Channel c, List<PointF> pts) {
        if (pts == null || pts.size() < 2) pts = createDefaultPoints();
        switch (c) {
            case RED: pointsR = pts; break;
            case GREEN: pointsG = pts; break;
            case BLUE: pointsB = pts; break;
            default: pointsRGB = pts; break;
        }
        invalidate();
        notifyListener();
    }

    public int[] getLutRGB() { return generateLUT(pointsRGB); }
    public int[] getLutR() { return generateLUT(pointsR); }
    public int[] getLutG() { return generateLUT(pointsG); }
    public int[] getLutB() { return generateLUT(pointsB); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        // Draw Grid
        for (int i=1; i<4; i++) {
            canvas.drawLine(w*i/4, 0, w*i/4, h, gridPaint);
            canvas.drawLine(0, h*i/4, w, h*i/4, gridPaint);
        }
        canvas.drawRect(0, 0, w, h, borderPaint);

        int channelColor;
        switch (currentChannel) {
            case RED: channelColor = Color.parseColor("#FF453A"); break;
            case GREEN: channelColor = Color.parseColor("#32D74B"); break;
            case BLUE: channelColor = Color.parseColor("#0A84FF"); break;
            default: channelColor = Color.WHITE; break;
        }
        linePaint.setColor(channelColor);
        
        // Gradient fill under curve
        fillPaint.setColor(channelColor);
        fillPaint.setAlpha(30);

        List<PointF> points = getCurrentPoints();
        Collections.sort(points, (o1, o2) -> Float.compare(o1.x, o2.x));

        if (points.size() >= 2) {
            Path path = new Path();
            Path fillPath = new Path();
            
            int[] lut = generateLUT(points);
            
            float startY = h - (lut[0] / 255f * h);
            path.moveTo(0, startY);
            fillPath.moveTo(0, h);
            fillPath.lineTo(0, startY);
            
            for (int i = 0; i < 256; i+=4) { // stride 4 for perf
                float x = (i / 255f) * w;
                float y = h - (lut[i] / 255f * h);
                path.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            float endY = h - (lut[255] / 255f * h);
            path.lineTo(w, endY);
            fillPath.lineTo(w, endY);
            fillPath.lineTo(w, h);
            fillPath.close();
            
            canvas.drawPath(fillPath, fillPaint);
            canvas.drawPath(path, linePaint);
        }

        for (int i=0; i<points.size(); i++) {
            PointF p = points.get(i);
            float rad = (i == activePointIndex) ? 24f : 16f;
            canvas.drawCircle(p.x * w, p.y * h, rad, pointPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float w = getWidth();
        float h = getHeight();
        float nx = Math.max(0, Math.min(1, x / w));
        float ny = Math.max(0, Math.min(1, y / h));

        List<PointF> points = getCurrentPoints();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                float minDesc = 0.1f; 
                activePointIndex = -1;
                for (int i = 0; i < points.size(); i++) {
                    PointF p = points.get(i);
                    if (Math.hypot(p.x - nx, p.y - ny) < minDesc) {
                        activePointIndex = i;
                        break;
                    }
                }
                
                if (activePointIndex == -1) {
                    if (points.size() < 10) { // Limit points
                        points.add(new PointF(nx, ny));
                        activePointIndex = points.size() - 1;
                        invalidate();
                        notifyListener();
                    }
                }
                break;
                
            case MotionEvent.ACTION_MOVE:
                if (activePointIndex != -1) {
                    PointF p = points.get(activePointIndex);
                    // Anchor endpoints horizontally
                    if (activePointIndex == 0) p.x = 0; 
                    else if (activePointIndex == points.size()-1) p.x = 1; 
                    else {
                         // Don't cross neighbors
                         PointF prev = points.get(activePointIndex-1);
                         PointF next = points.get(activePointIndex+1);
                         p.x = Math.max(prev.x + 0.01f, Math.min(next.x - 0.01f, nx));
                    }
                    p.y = ny;
                    invalidate();
                    notifyListener();
                }
                break;
                
            case MotionEvent.ACTION_UP:
                activePointIndex = -1;
                break;
        }
        return true;
    }
    
    private int[] generateLUT(List<PointF> knots) {
        int[] lut = new int[256];
        if (knots.size() < 2) return lut;

        int n = knots.size();
        float[] x = new float[n];
        float[] y = new float[n];
        
        for(int i=0; i<n; i++) {
            x[i] = knots.get(i).x * 255f;
            y[i] = (1f - knots.get(i).y) * 255f;
        }
        
        for (int i = 0; i < 256; i++) {
            lut[i] = (int) Math.max(0, Math.min(255, interpolate(i, x, y)));
        }
        return lut;
    }

    private float interpolate(float val, float[] x, float[] y) {
        int n = x.length;
        if (val <= x[0]) return y[0];
        if (val >= x[n-1]) return y[n-1];
        
        int i = 0;
        while (val > x[i+1]) i++;
        
        float t = (val - x[i]) / (x[i+1] - x[i]);
        return y[i] + t * (y[i+1] - y[i]); 
    }
}