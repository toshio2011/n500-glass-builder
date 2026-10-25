package com.n500.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Single premium 5-way D-pad with touch hit testing and real key callbacks. */
public final class GlassDpad extends View {
    public interface Listener { void onDirection(String direction); }
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Listener listener;
    private int pressed = -1;
    private final String[] keys = {"UP", "RIGHT", "DOWN", "LEFT", "OK"};

    public GlassDpad(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Directional pad: Up, Right, Down, Left and OK");
        setClickable(true);
    }
    @Override protected void onMeasure(int w, int h) {
        int desired = (int) (280 * getResources().getDisplayMetrics().density);
        int width = Math.min(desired, MeasureSpec.getSize(w));
        setMeasuredDimension(width, width);
    }
    private void ring(Canvas c, float x, float y, float radius, int color, float stroke) {
        p.reset(); p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        c.drawCircle(x,y,radius,p);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx=getWidth()/2f, cy=getHeight()/2f, r=Math.min(cx,cy)*.89f;
        p.reset();p.setAntiAlias(true);p.setStyle(Paint.Style.FILL);
        p.setShadowLayer(r*.12f,0,r*.035f,0x90000000);
        p.setShader(new LinearGradient(cx-r,cy-r,cx+r,cy+r,
                new int[]{0xff364d64,0xff19283c,0xff121b2c},null,Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,r,p);
        p.clearShadowLayer();p.setShader(null);
        ring(c,cx,cy,r-2,0x6682b0cb,1.8f);
        ring(c,cx,cy,r*.70f,0x426b8aa2,1.5f);

        for(int i=0;i<4;i++){
            p.setColor(pressed==i ? 0xff5adbc8 : 0xffdcebf2);
            p.setStyle(Paint.Style.FILL);
            float angle=(float) Math.toRadians(-90 + 90*i);
            float tx=cx+(float)Math.cos(angle)*r*.71f;
            float ty=cy+(float)Math.sin(angle)*r*.71f;
            Path triangle=new Path();
            float size=r*.09f;
            if(i==0){triangle.moveTo(tx,ty-size);triangle.lineTo(tx-size,ty+size);triangle.lineTo(tx+size,ty+size);}
            if(i==1){triangle.moveTo(tx+size,ty);triangle.lineTo(tx-size,ty-size);triangle.lineTo(tx-size,ty+size);}
            if(i==2){triangle.moveTo(tx,ty+size);triangle.lineTo(tx-size,ty-size);triangle.lineTo(tx+size,ty-size);}
            if(i==3){triangle.moveTo(tx-size,ty);triangle.lineTo(tx+size,ty-size);triangle.lineTo(tx+size,ty+size);}
            triangle.close();c.drawPath(triangle,p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed==4 ? 0xff57d6c5 : 0xff253e51);
        c.drawCircle(cx,cy,r*.38f,p);
        ring(c,cx,cy,r*.38f,0x668fdbd4,2.2f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(pressed==4 ? 0xff0b202a : Color.WHITE);
        p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));
        p.setTextSize(r*.22f);
        p.setStyle(Paint.Style.FILL);
        c.drawText("OK",cx,cy+r*.077f,p);
    }
    private int findTarget(float x,float y) {
        float dx=x-getWidth()/2f,dy=y-getHeight()/2f;
        float dist=(float)Math.hypot(dx,dy);
        float r=Math.min(getWidth(),getHeight())*.445f;
        if(dist>r) return -1;
        if(dist<r*.40f) return 4;
        if(Math.abs(dx)>Math.abs(dy)) return dx>0?1:3;
        return dy>0?2:0;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(e.getAction()==MotionEvent.ACTION_DOWN) {
            pressed=findTarget(e.getX(),e.getY());
            invalidate();return true;
        }
        if(e.getAction()==MotionEvent.ACTION_UP) {
            int selected=findTarget(e.getX(),e.getY());
            int old=pressed;
            pressed=-1;invalidate();
            if(selected>=0 && selected==old && listener!=null) {
                listener.onDirection(keys[selected]);
                performClick();
            }
            return true;
        }
        if(e.getAction()==MotionEvent.ACTION_CANCEL) {
            pressed=-1;invalidate();return true;
        }
        return true;
    }
    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
