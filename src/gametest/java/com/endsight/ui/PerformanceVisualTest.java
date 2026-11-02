package com.endsight.ui;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Render the saved pre-audit helpers and the optimized helpers on the actual GPU. */
public final class PerformanceVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1200, 900);
        context.runOnClient(mc -> {
            GLFW.glfwHideWindow(mc.getWindow().handle());
            mc.options.guiScale().set(2);
            mc.options.hideGui = true;
            mc.resizeGui();
            mc.setScreen(new Shapes(false));
        });
        context.waitTicks(5);
        context.takeScreenshot("performance-original");
        context.runOnClient(mc -> mc.setScreen(new Shapes(true)));
        context.waitTicks(5);
        context.takeScreenshot("performance-optimized");
        context.runOnClient(mc -> mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
        System.out.println("PERFORMANCE GPU RENDER CHECK: original and optimized screenshots captured for pixel comparison");
    }

    private static final class Shapes extends Screen {
        private final boolean optimized;
        Shapes(boolean optimized) { super(Component.literal("Performance render check")); this.optimized = optimized; }

        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float partial) {
            g.fill(0,0,width,height,0xFF222731);
            for(int y=0;y<height;y+=12) for(int x=0;x<width;x+=12)
                if((x/12+y/12)%2==0) g.fill(x,y,x+12,y+12,0xFF4A5661);
            for(int row=0;row<4;row++) for(int col=0;col<8;col++) {
                int x=8+col*72,y=8+row*49,r=col==0?0:col+1,colour=0x8755AADD;
                boolean tl=(col&1)!=0,tr=(col&2)!=0,bl=(col&4)!=0,br=(row&1)!=0;
                if(optimized) Draw.roundedRect(g,x,y,66,40,r,colour,tl,tr,bl,br);
                else LegacyDraw.roundedRect(g,x,y,66,40,r,colour,tl,tr,bl,br);
                if(optimized) Draw.roundedRing(g,x+3,y+3,59,33,r,0xB8EE8822);
                else LegacyDraw.roundedRing(g,x+3,y+3,59,33,r,0xB8EE8822);
                g.text(font,"r"+r,x+8,y+13,0xFFFFFFFF);
            }
            var pose=g.pose();pose.pushMatrix();pose.translate(15f,218f);pose.scale(.85f,.85f);pose.rotate(.13f);
            if(optimized) Draw.glass(g,0,0,180,75,12); else LegacyDraw.glass(g,0,0,180,75,12);
            if(optimized) Draw.roundedRect(g,8,8,100,30,15,0x6044DD88); else LegacyDraw.roundedRect(g,8,8,100,30,15,0x6044DD88);
            pose.popMatrix();
            g.enableScissor(230,215,380,285);
            if(optimized) Draw.roundedOutline(g,220,205,180,100,18,0x7CFF55AA,0x99441133);
            else LegacyDraw.roundedOutline(g,220,205,180,100,18,0x7CFF55AA,0x99441133);
            g.disableScissor();
            for(int row=0;row<4;row++) {
                if(optimized) g.nextStratum();
                for(int col=0;col<9;col++) {
                    int x=8+col*19,y=325+row*19;
                    if(optimized) { Draw.roundedRing(g,x,y,18,18,2,0x28FFFFFF);Draw.badge(g,x,y,18,0x7CFF00FF);Draw.badge(g,x+1,y+1,16,0xBE25172F); }
                    else { LegacyDraw.roundedRing(g,x,y,18,18,2,0x28FFFFFF);badge(g,x,y,18,0x7CFF00FF);badge(g,x+1,y+1,16,0xBE25172F); }
                    g.fill(x+3,y+3,x+15,y+15,0xAACC7744);
                }
            }
            g.text(font,"Same shapes, alpha, clipping, transforms and item borders",8,430,0xFFFFFFFF);
        }

        private static void badge(GuiGraphicsExtractor g,int x,int y,int size,int colour) {
            LegacyDraw.rect(g,x+4,y,size-8,2,colour);LegacyDraw.rect(g,x+2,y+2,size-4,2,colour);
            LegacyDraw.rect(g,x,y+4,size,size-8,colour);LegacyDraw.rect(g,x+2,y+size-4,size-4,2,colour);
            LegacyDraw.rect(g,x+4,y+size-2,size-8,2,colour);
        }
    }

    /** Original band arithmetic retained only in this test mod as the GPU reference. */
    private static final class LegacyDraw {
        static void rect(GuiGraphicsExtractor g,int x,int y,int w,int h,int colour) {
            if(w>0&&h>0) g.fill(x,y,x+w,y+h,colour);
        }
        static void roundedRect(GuiGraphicsExtractor g,int x,int y,int w,int h,int radius,int colour) {
            roundedRect(g,x,y,w,h,radius,colour,true,true,true,true);
        }
        static void roundedRect(GuiGraphicsExtractor g,int x,int y,int w,int h,int radius,int colour,
                                boolean tl,boolean tr,boolean bl,boolean br) {
            if(w<=0||h<=0)return;
            int r=Math.min(radius,Math.min(w,h)/2);
            if(r<=0||!tl&&!tr&&!bl&&!br) {rect(g,x,y,w,h,colour);return;}
            g.fill(x,y+r,x+w,y+h-r,colour);
            for(int i=0;i<r;i++) {
                double dy=r-i-.5;
                int inset=(int)Math.round(r-Math.sqrt(Math.max(0,(double)r*r-dy*dy)));
                g.fill(x+(tl?inset:0),y+i,x+w-(tr?inset:0),y+i+1,colour);
                g.fill(x+(bl?inset:0),y+h-i-1,x+w-(br?inset:0),y+h-i,colour);
            }
        }
        static void roundedRing(GuiGraphicsExtractor g,int x,int y,int w,int h,int radius,int colour) {
            if(w<=1||h<=1)return;
            int r=Math.min(radius,Math.min(w,h)/2);
            rect(g,x+r,y,w-2*r,1,colour);rect(g,x+r,y+h-1,w-2*r,1,colour);
            rect(g,x,y+r,1,h-2*r,colour);rect(g,x+w-1,y+r,1,h-2*r,colour);
            for(int i=0;i<r;i++) {
                double dy=r-i-.5;
                int inset=(int)Math.round(r-Math.sqrt(Math.max(0,(double)r*r-dy*dy)));
                rect(g,x+inset,y+i,1,1,colour);rect(g,x+w-inset-1,y+i,1,1,colour);
                rect(g,x+inset,y+h-i-1,1,1,colour);rect(g,x+w-inset-1,y+h-i-1,1,1,colour);
            }
        }
        static void roundedOutline(GuiGraphicsExtractor g,int x,int y,int w,int h,int r,int border,int fill) {
            roundedRect(g,x,y,w,h,r,border);roundedRect(g,x+1,y+1,w-2,h-2,Math.max(0,r-1),fill);
        }
        static void glass(GuiGraphicsExtractor g,int x,int y,int w,int h,int r) {
            if(w<=2||h<=2)return;
            roundedOutline(g,x,y,w,h,r,Draw.alpha(Theme.text(),.14f),Draw.alpha(Theme.bg(),.5f));
            rect(g,x+r,y+1,w-2*r,1,Draw.alpha(Theme.text(),.10f));
        }
    }
}
