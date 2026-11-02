package com.endsight.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Compare against the old fill arithmetic and the game's actual rectangle vertex builder. */
final class ShapeGeometryChecks {
    private static int checks;
    private static volatile Object sink;
    private static final Matrix3x2fc IDENTITY = new Matrix3x2f();
    private record Vertex(float x, float y, float z, int colour) { }

    static void run() {
        for (Matrix3x2fc pose : List.of(IDENTITY, new Matrix3x2f().translate(3.5f, -8).scale(.85f, 1.25f),
                new Matrix3x2f().rotate(.27f))) {
            for (int w : new int[]{2, 3, 7, 18, 31}) for (int h : new int[]{2, 3, 10, 18, 37}) {
                for (int radius : new int[]{0, 2, 5, 80}) {
                    int r = Math.min(radius, Math.min(w, h) / 2);
                    for (int corners = 0; corners < 16; corners++) {
                        compare(ShapeRenderState.Kind.ROUNDED, pose, w, h, r, corners);
                    }
                    compare(ShapeRenderState.Kind.RING, pose, w, h, r, 15);
                }
                compare(ShapeRenderState.Kind.FRAME, pose, w, h, 0, 0);
                if (w >= 7 && h >= 10) compare(ShapeRenderState.Kind.CORNERS, pose, w, h, 5, 0);
                if (w >= 10 && h >= 10) compare(ShapeRenderState.Kind.BADGE, pose, w, h, 0, 0);
            }
        }
        check(Arrays.equals(pixels(inventory(false)), pixels(inventory(true))), "inventory borders, alpha, icons and overlap");
        if (Boolean.getBoolean("endsight.performanceBenchmark")) benchmark();
        System.out.println(checks + " batched shape geometry and compositing checks passed");
    }

    private static ShapeRenderState shape(ShapeRenderState.Kind kind, Matrix3x2fc pose,
                                           int x, int y, int w, int h, int r, int corners, int colour) {
        return new ShapeRenderState(kind, pose, x, y, w, h, r, corners, colour, null,
                new ScreenRectangle(x, y, w, h).transformMaxBounds(pose));
    }

    private static void compare(ShapeRenderState.Kind kind, Matrix3x2fc pose, int w, int h, int r, int corners) {
        ShapeRenderState shape = shape(kind, pose, -3, 5, w, h, r, corners, 0x8B14AB72);
        Recorder before = new Recorder(), after = new Recorder();
        reference(shape).forEach(rect -> rect.buildVertices(before));
        shape.buildVertices(after);
        check(before.vertices.equals(after.vertices), kind + " exact vertices, colours and winding: " + w + "/" + h + "/" + r + "/" + corners);
    }

    /** Independent reference copied from the pre-audit Draw/RarityOutline implementation. */
    private static List<ColoredRectangleRenderState> reference(ShapeRenderState s) {
        List<ColoredRectangleRenderState> out = new ArrayList<>();
        int x=s.x(), y=s.y(), w=s.w(), h=s.h(), r=s.radius(), mask=s.corners();
        switch (s.kind()) {
            case ROUNDED -> {
                rect(out,s,x,y+r,w,h-2*r);
                for (int i=0;i<r;i++) {
                    double dy=r-i-.5;
                    int inset=(int)Math.round(r-Math.sqrt(Math.max(0,(double)r*r-dy*dy)));
                    int left=(mask&1)!=0?inset:0, right=(mask&2)!=0?inset:0;
                    rect(out,s,x+left,y+i,w-left-right,1);
                    left=(mask&4)!=0?inset:0; right=(mask&8)!=0?inset:0;
                    rect(out,s,x+left,y+h-i-1,w-left-right,1);
                }
            }
            case RING -> {
                rect(out,s,x+r,y,w-2*r,1); rect(out,s,x+r,y+h-1,w-2*r,1);
                rect(out,s,x,y+r,1,h-2*r); rect(out,s,x+w-1,y+r,1,h-2*r);
                for(int i=0;i<r;i++) {
                    double dy=r-i-.5;
                    int inset=(int)Math.round(r-Math.sqrt(Math.max(0,(double)r*r-dy*dy)));
                    rect(out,s,x+inset,y+i,1,1); rect(out,s,x+w-inset-1,y+i,1,1);
                    rect(out,s,x+inset,y+h-i-1,1,1); rect(out,s,x+w-inset-1,y+h-i-1,1,1);
                }
            }
            case FRAME -> {
                rect(out,s,x+1,y,w-2,1); rect(out,s,x+1,y+h-1,w-2,1);
                rect(out,s,x,y+1,1,h-2); rect(out,s,x+w-1,y+1,1,h-2);
            }
            case CORNERS -> {
                rect(out,s,x+1,y,r,1); rect(out,s,x,y+1,1,r);
                rect(out,s,x+w-1-r,y,r,1); rect(out,s,x+w-1,y+1,1,r);
                rect(out,s,x+1,y+h-1,r,1); rect(out,s,x,y+h-1-r,1,r);
                rect(out,s,x+w-1-r,y+h-1,r,1); rect(out,s,x+w-1,y+h-1-r,1,r);
            }
            case BADGE -> {
                rect(out,s,x+4,y,w-8,2); rect(out,s,x+2,y+2,w-4,2);
                rect(out,s,x,y+4,w,h-8); rect(out,s,x+2,y+h-4,w-4,2);
                rect(out,s,x+4,y+h-2,w-8,2);
            }
        }
        return out;
    }

    private static void rect(List<ColoredRectangleRenderState> out, ShapeRenderState s,int x,int y,int w,int h) {
        if (w>0&&h>0) out.add(new ColoredRectangleRenderState(RenderPipelines.GUI,TextureSetup.noTexture(),
                s.pose(),x,y,x+w,y+h,s.colour(),s.colour(),null));
    }

    private static void add(GuiRenderState state, ShapeRenderState shape, boolean batched) {
        if (batched) state.addGuiElement(shape);
        else reference(shape).forEach(state::addGuiElement);
    }

    private static GuiRenderState inventory(boolean batched) {
        GuiRenderState state = new GuiRenderState();
        for (int row=0;row<4;row++) {
            if (batched) state.nextStratum();
            for (int col=0;col<9;col++) {
                int x=4+col*18,y=4+row*18;
                add(state,shape(ShapeRenderState.Kind.RING,IDENTITY,x-1,y-1,18,18,2,15,0x28FFFFFF),batched);
                add(state,shape(ShapeRenderState.Kind.BADGE,IDENTITY,x-1,y-1,18,18,0,0,0x7CFF00FF),batched);
                add(state,shape(ShapeRenderState.Kind.BADGE,IDENTITY,x,y,16,16,0,0,0xBE25172F),batched);
                state.addGuiElement(new ColoredRectangleRenderState(RenderPipelines.GUI,TextureSetup.noTexture(),
                        IDENTITY,x,y,x+16,y+16,0xAECC7744,0xAECC7744,null));
            }
        }
        state.addGuiElement(new ColoredRectangleRenderState(RenderPipelines.GUI,TextureSetup.noTexture(),
                IDENTITY,3,3,30,35,0x80443322,0x80443322,null));
        return state;
    }

    private static int[] pixels(GuiRenderState state) {
        int[] pixels=new int[180*90];
        state.forEachElement(element -> {
            Recorder recorder=new Recorder(); element.buildVertices(recorder);
            List<Vertex> vertices=recorder.vertices;
            for(int i=0;i<vertices.size();i+=4) {
                Vertex a=vertices.get(i), b=vertices.get(i+2); int c=a.colour(), alpha=c>>>24;
                for(int y=(int)a.y();y<(int)b.y();y++) for(int x=(int)a.x();x<(int)b.x();x++) {
                    int old=pixels[y*180+x], result=0xFF000000;
                    for(int shift:new int[]{0,8,16}) result|=((((c>>shift)&255)*alpha+((old>>shift)&255)*(255-alpha))/255)<<shift;
                    pixels[y*180+x]=result;
                }
            }
        },GuiRenderState.TraverseRange.ALL);
        return pixels;
    }

    private static void benchmark() {
        for(int i=0;i<60;i++) { sink=inventory(false); sink=inventory(true); }
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        for(boolean batched:new boolean[]{false,true}) {
            long allocated=bean.getCurrentThreadAllocatedBytes(), start=System.nanoTime();
            for(int i=0;i<500;i++) sink=inventory(batched);
            System.out.printf(Locale.ROOT,"Inventory submission %s: %.3f ms/frame, %.0f allocated bytes/frame%n",
                    batched?"batched":"original",(System.nanoTime()-start)/500_000_000.0,
                    (bean.getCurrentThreadAllocatedBytes()-allocated)/500.0);
        }
    }

    private static final class Recorder implements VertexConsumer {
        final List<Vertex> vertices=new ArrayList<>();
        public VertexConsumer addVertex(float x,float y,float z) { vertices.add(new Vertex(x,y,z,0));return this; }
        public VertexConsumer setColor(int colour) { int n=vertices.size()-1; Vertex v=vertices.get(n);vertices.set(n,new Vertex(v.x(),v.y(),v.z(),colour));return this; }
        public VertexConsumer setColor(int r,int g,int b,int a) { return setColor(a<<24|r<<16|g<<8|b); }
        public VertexConsumer setUv(float u,float v) { return this; }
        public VertexConsumer setUv1(int u,int v) { return this; }
        public VertexConsumer setUv2(int u,int v) { return this; }
        public VertexConsumer setNormal(float x,float y,float z) { return this; }
        public VertexConsumer setLineWidth(float width) { return this; }
    }

    private static void check(boolean ok,String message) { checks++;if(!ok)throw new AssertionError(message); }
}
