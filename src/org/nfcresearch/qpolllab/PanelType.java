package org.nfcresearch.qpolllab;

/**
 * Huawei JD79665AA panel variants; selection is physical, never inferred from D1.
 *
 * ===== A0 布局实测校准（nova14 Pro / SE0398NZ07-FND-A0，双 COF 半屏拼接）=====
 * 测试方法（每轮 ~8 分钟）：
 *   1) 只改下面 VARIANT 的数字 → Commit changes
 *   2) Actions → build-apk → Run workflow → 绿灯后 Releases 下载安装
 *   3) App 内选 A0 → 发送测试卡 → 正对屏幕拍照发回
 * 现象基线：A1(恒等) 直发 = 左半正确 + 右半镜像 ⇒ 右半 COF 列序反接。
 */
public enum PanelType {
    A0, A1;

    /** A0 布局校准值（1~8），由 MainActivity 的"布局"下拉框在运行时设置。 */
    public static int variant = 2;

    public static PanelType parse(String value) {
        if("A0".equals(value))return A0;
        if("A1".equals(value))return A1;
        throw new IllegalArgumentException("请先选择屏幕类型 A0 或 A1");
    }
    public byte[] pack(byte[] codes) {
        return map(PanelProtocol.packCodes(codes,PanelProtocol.WIDTH,PanelProtocol.HEIGHT),false);
    }
    /** A0 流内 552 个"列段"（每段 192 字节）的重排目标位置。 */
    private static int target(int row,int n){
        switch(variant){
            default:
            case 1: return row<n/2?2*row:2*n-1-2*row;          // 1 基线：奇偶交错+奇半倒序（原作者推测版）
            case 2: return row<n/2?row:n+n/2-1-row;             // 2 后半倒序原位（右COF反接补偿）★照片证据首选
            case 3: return row<n/2?n/2-1-row:row;               // 3 前半倒序
            case 4: return row<n/2?row+n/2:row-n/2;             // 4 两半交换
            case 5: return n-1-row;                             // 5 全部倒序
            case 6: return row<n/2?2*row:2*(row-n/2)+1;         // 6 奇偶交错（不倒序）
            case 7: return row<n/2?n-1-row:row-n/2;             // 7 交叉：左半倒序进右区，右半直进左区
            case 8: return row<n/2?row+n/2:n-1-row;             // 8 交叉：左半直进右区，右半倒序进左区
        }
    }
    private byte[] map(byte[] raw,boolean inverse) {
        if(raw.length!=PanelProtocol.IMAGE_BYTES)throw new IllegalArgumentException("屏幕数据长度错误");
        if(this==A1)return raw.clone();
        final int rows=PanelProtocol.WIDTH,stride=PanelProtocol.HEIGHT/4;
        int[] fwd=new int[rows];
        int[] inv=new int[rows];
        for(int row=0;row<rows;row++){ fwd[row]=target(row,rows); inv[fwd[row]]=row; }
        byte[] out=new byte[raw.length];
        for(int row=0;row<rows;row++){
            int from=inverse?inv[row]:row;
            int to=inverse?row:fwd[row];
            System.arraycopy(raw,from*stride,out,to*stride,stride);
        }
        return out;
    }
    public int[] preview(byte[] packed) {
        return ImageRaster.preview(map(packed,true),PanelProtocol.WIDTH,PanelProtocol.HEIGHT);
    }
    /** Stored scan 00 uses bottom-up pixels; scan 01 uses top-down pixels in each row. */
    public static byte[] forScan(byte[] packed,boolean topDown) {
        if(packed.length!=PanelProtocol.IMAGE_BYTES)throw new IllegalArgumentException("屏幕数据长度错误");
        if(!topDown)return packed.clone();
        int stride=PanelProtocol.HEIGHT/4;byte[] out=new byte[packed.length];
        for(int row=0;row<PanelProtocol.WIDTH;row++)for(int i=0;i<stride;i++) {
            int b=packed[row*stride+stride-1-i]&255;
            out[row*stride+i]=(byte)(((b&3)<<6)|((b&12)<<2)|((b&48)>>2)|((b&192)>>6));
        }
        return out;
    }
}
