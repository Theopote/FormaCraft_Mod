package com.formacraft.client.ui.panel;
/** GUI-scaled floating bounds shared by rendering, clicking and title dragging. */
public final class PatchPanelPlacement {
    public record Bounds(int x,int y,int width,int height,int columns) {
        public boolean contains(double px,double py) {return px>=x&&px<x+width&&py>=y&&py<y+height;}
        public boolean title(double px,double py) {return contains(px,py)&&py<y+22;}
        public int buttonWidth() {return (width-16-(columns-1)*4)/columns;}
        public int buttonX(int i) {return x+8+(i%columns)*(buttonWidth()+4);}
        public int buttonY(int i) {return y+height-8-(columns==4?16:36)+(i/columns)*20;}
    }
    private int x=Integer.MIN_VALUE,y=Integer.MIN_VALUE;
    public Bounds layout(int screenW,int screenH,int sidebarRight) {
        int left=Math.min(Math.max(8,sidebarRight+8),Math.max(8,screenW-72));
        int width=Math.max(48,Math.min(240,screenW-left-8));
        int cols=width>=180?4:2;
        int height=Math.min(screenH-16,cols==4?110:130);
        if(x==Integer.MIN_VALUE) {x=screenW-width-8;y=screenH-height-8;}
        x=Math.max(left,Math.min(screenW-width-8,x));y=Math.max(8,Math.min(screenH-height-8,y));
        return new Bounds(x,y,width,height,cols);
    }
    public void move(int x,int y) {this.x=x;this.y=y;}
}
