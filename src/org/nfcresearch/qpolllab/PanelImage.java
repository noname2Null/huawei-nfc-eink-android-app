package org.nfcresearch.qpolllab;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.InputStream;

/** Offline preview and 2-bit factory-layout image encoding. */
public final class PanelImage {
    public static final int[] PALETTE={ImageRaster.color(0),ImageRaster.color(1),ImageRaster.color(2),ImageRaster.color(3)};
    public static final class Prepared {
        public final Bitmap preview;
        public final byte[] packed;
        public final String settings;
        public final String panelType;
        Prepared(Bitmap preview,byte[] packed,String settings,String panelType){this.preview=preview;this.packed=packed;this.settings=settings;this.panelType=panelType;}
    }
    public static Bitmap load(ContentResolver resolver,Uri uri) throws Exception {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        try(InputStream in=resolver.openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
        if(options.outWidth<1||options.outHeight<1)throw new IllegalArgumentException("无法识别图片");
        int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>2048)sample*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap decoded;
        try(InputStream in=resolver.openInputStream(uri)){decoded=BitmapFactory.decodeStream(in,null,options);}
        if(decoded==null)throw new IllegalArgumentException("图片解码失败");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream in=resolver.openInputStream(uri)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}
        catch(Exception ignored) { }
        Matrix m=new Matrix();
        switch(orientation) {
            case 2:m.setScale(-1,1);break;
            case 3:m.setRotate(180);break;
            case 4:m.setScale(1,-1);break;
            case 5:m.setRotate(90);m.postScale(-1,1);break;
            case 6:m.setRotate(90);break;
            case 7:m.setRotate(270);m.postScale(-1,1);break;
            case 8:m.setRotate(270);break;
            default:break;
        }
        return m.isIdentity()?decoded:Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),m,true);
    }
    public static Bitmap testPattern() {
        Bitmap b=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.WHITE);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTextSize(44);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText("NFC 刷新测试",44,85,p);p.setTextSize(27);c.drawText("SN220U / Q-Poll",44,132,p);
        for(int i=0;i<4;i++){p.setColor(PALETTE[i]);c.drawRect(i*138,180,(i+1)*138,570,p);}
        p.setColor(Color.BLACK);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);c.drawRect(3,3,549,765,p);p.setStyle(Paint.Style.FILL);
        p.setTextSize(28);c.drawText("552 × 768",44,640,p);c.drawText("顶部 ↑    黑 白 黄 红",44,690,p);
        return b;
    }
    public static Bitmap rotated(Bitmap source,int rotation) {
        if(rotation==0)return source;
        Matrix rotate=new Matrix();rotate.setRotate(rotation);
        return Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),rotate,true);
    }
    /** adj 七元调节(全部0=原样)：{消噪模糊0~3, 饱和度0~3(×1.0~1.3), 清晰度0~2, 冷暖-1~1, 黄墨倾向0~2, 红墨倾向0~2, 明暗-1~1}。可任意叠加。 */
    public static Prepared prepare(Bitmap source,int rotation,boolean cover,boolean dither,int block,boolean binary,int threshold,boolean cropped,String panelType,int[] adj) {
        int denoiseLevel=adj[0],colorLevel=adj[1],sharpLevel=adj[2],warmLevel=adj[3],yellowBias=adj[4],redBias=adj[5],toneLevel=adj[6];
        if(block>1)dither=false;
        Bitmap input=rotated(source,rotation);
        Bitmap fitted=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(fitted);canvas.drawColor(Color.WHITE);
        float sx=552f/input.getWidth(),sy=768f/input.getHeight(),scale=cover?Math.max(sx,sy):Math.min(sx,sy);
        float w=input.getWidth()*scale,h=input.getHeight()*scale;
        canvas.drawBitmap(input,null,new RectF((552-w)/2,(768-h)/2,(552+w)/2,(768+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pixels=new int[552*768];
        fitted.getPixels(pixels,0,552,0,0,552,768);
        int passes=Math.max(0,Math.min(3,denoiseLevel));
        for(int i=0;i<passes;i++)pixels=blur(pixels,552,768);
        if(sharpLevel>0){int[] soft=blur(pixels,552,768);pixels=sharpen(pixels,soft,0.3f*sharpLevel);}
        if(warmLevel!=0)pixels=warmify(pixels,warmLevel);
        pixels=saturate(pixels,1f+0.1f*Math.max(0,Math.min(3,colorLevel)));
        float[] bias={1f,1f,1f,1f};
        bias[2]*=1f-0.12f*Math.max(0,Math.min(2,yellowBias));
        bias[3]*=1f-0.12f*Math.max(0,Math.min(2,redBias));
        if(toneLevel>0)bias[1]*=0.88f;else if(toneLevel<0)bias[0]*=0.88f;
        byte[] codes=ImageRaster.quantize(pixels,552,768,binary,threshold,dither,block,bias);
        PanelType type=panelType.isEmpty()?PanelType.A1:PanelType.parse(panelType);
        byte[] packed=type.pack(codes);
        Bitmap image=Bitmap.createBitmap(type.preview(packed),552,768,Bitmap.Config.ARGB_8888);
        String settings="屏幕："+(panelType.isEmpty()?"尚未选择":panelType)+" · "+(binary?"黑白二值化 · 阈值 "+threshold:"黑白黄红四色")+" · 简化："+(block==1?"原始细节":block+"×"+block+" 色块")+" · 抖动："+(dither?"开启":"关闭")+" · 消噪["+Math.max(0,Math.min(3,denoiseLevel))+"] · 饱和["+String.format("%.1f",1f+0.1f*Math.max(0,Math.min(3,colorLevel)))+"] · 清晰["+Math.max(0,Math.min(2,sharpLevel))+"] · 冷暖["+warmLevel+"] · 黄["+Math.max(0,Math.min(2,yellowBias))+"] · 红["+Math.max(0,Math.min(2,redBias))+"] · 明暗["+toneLevel+"] · "+(cropped?"已手动裁切 · ":"")+"旋转 "+rotation+"° · "+(cover?"填满":"留白");
        return new Prepared(image,packed,settings,panelType);
    }
    /** [1,2,1]/4 可分离模糊，去高频噪点。 */
    private static int[] blur(int[] px,int w,int h){
        int[] tmp=new int[px.length];
        for(int y=0;y<h;y++){int o=y*w;tmp[o]=px[o];
            for(int x=1;x<w-1;x++)tmp[o+x]=mix3(px[o+x-1],px[o+x],px[o+x+1]);
            tmp[o+w-1]=px[o+w-1];}
        int[] out=new int[px.length];
        for(int x=0;x<w;x++)out[x]=tmp[x];
        for(int y=1;y<h-1;y++){int o=y*w;
            for(int x=0;x<w;x++)out[o+x]=mix3(tmp[o-w+x],tmp[o+x],tmp[o+w+x]);}
        for(int x=0;x<w;x++)out[(h-1)*w+x]=tmp[(h-1)*w+x];
        return out;
    }
    private static int mix3(int a,int b,int c){
        return 0xff000000|(((((a>>>16)&255)+2*((b>>>16)&255)+((c>>>16)&255))>>2)<<16)
                |(((((a>>>8)&255)+2*((b>>>8)&255)+((c>>>8)&255))>>2)<<8)
                |((((a&255)+2*(b&255)+(c&255))>>2));
    }
    /** 清晰度：原图 + k×(原图-柔焦)，经典反锐化掩模。 */
    private static int[] sharpen(int[] px,int[] soft,float k){
        int[] out=new int[px.length];
        for(int i=0;i<px.length;i++){int p=px[i],s=soft[i];
            int r=clamp8(((p>>>16)&255)+(int)((((p>>>16)&255)-((s>>>16)&255))*k));
            int g=clamp8(((p>>>8)&255)+(int)((((p>>>8)&255)-((s>>>8)&255))*k));
            int b=clamp8((p&255)+(int)(((p&255)-(s&255))*k));
            out[i]=0xff000000|(r<<16)|(g<<8)|b;
        }
        return out;
    }
    /** 冷暖：>0 偏暖(加红减蓝)，<0 偏冷。 */
    private static int[] warmify(int[] px,int warm){
        int[] out=new int[px.length];
        float rw=1+0.07f*warm,bw=1-0.07f*warm;
        for(int i=0;i<px.length;i++){int p=px[i];
            int r=clamp8((int)(((p>>>16)&255)*rw)),g=(p>>>8)&255,b=clamp8((int)(((p&255))*bw));
            out[i]=0xff000000|(r<<16)|(g<<8)|b;
        }
        return out;
    }
    /** 围绕均值拉伸色度，把颜色喂向四色墨的饱和度。 */
    private static int[] saturate(int[] px,float k){
        int[] out=new int[px.length];
        for(int i=0;i<px.length;i++){int p=px[i];
            int r=(p>>>16)&255,g=(p>>>8)&255,b=p&255,mean=(r+g+b)/3;
            int r2=(int)(mean+(r-mean)*k),g2=(int)(mean+(g-mean)*k),b2=(int)(mean+(b-mean)*k);
            out[i]=0xff000000|(clamp8(r2)<<16)|(clamp8(g2)<<8)|clamp8(b2);
        }
        return out;
    }
    private static int clamp8(int v){return v<0?0:v>255?255:v;}
}
