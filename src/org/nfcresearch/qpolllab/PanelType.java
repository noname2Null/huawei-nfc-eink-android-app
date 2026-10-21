package org.nfcresearch.qpolllab;

/**
 * Huawei JD79665AA panel variants; selection is physical, never inferred from D1.
 *
 * ===== A0 布局校准 v2（nova14 Pro / SE0398NZ07-FND-A0）=====
 * 标记图 X 光结论：该面板为"挤压-镜像"布线——左半屏采样显示全图，右半屏显示镜像全图。
 * 修正公式（1 号，已对全部 552 列数学验证）：
 *   out[2j]   = raw[275-j]   （前半数据倒序 → 偶数槽）
 *   out[2j+1] = raw[276+j]   （后半数据正序 → 奇数槽）
 * 测试：App 内"布局"下拉框切换 1~8，每刷一次约 1 分钟，无需重新构建。
 */
public enum PanelType {
    A0, A1;

    /** A0 布局候选（1~8），由 MainActivity 的"布局"下拉框在运行时设置。 */
    public static int variant = 1;

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
        final int h=n/2;
        switch(variant){
            default:
            case 1: return row<h?2*(h-1-row):2*(row-h)+1;   // 1 ★挤压-镜像修正（标记图验证）
            case 2: return row<h?2*(h-1-row)+1:2*(row-h);   // 2 交叉反向
            case 3: return row<h?2*row:2*n-1-2*row;         // 3 基线：奇偶交错+奇半倒序
            case 4: return row;                             // 4 恒等（=A1）
            case 5: return row<h?2*(h-1-row):2*(n-1-row)+1; // 5 前后半均倒序进槽
            case 6: return row<h?2*row:2*(row-h)+1;         // 6 奇偶交错（不倒序）
            case 7: return row<h?2*row+1:2*(n-1-row);       // 7 前半正序→奇槽，后半倒序→偶槽
            case 8: return row<h?2*(h-1-row)+1:2*(row-h);   // 8 前半倒序→奇槽，后半正序→偶槽
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
