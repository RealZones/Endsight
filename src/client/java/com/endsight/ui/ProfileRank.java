package com.endsight.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import java.util.Locale;

/** Native compact rank lettering when the server pack is loaded; readable fallback elsewhere. */
final class ProfileRank {
    private static final Identifier NATIVE_FONT=Identifier.fromNamespaceAndPath("iconographic","mc5");
    private static final Identifier FONT_RESOURCE=Identifier.fromNamespaceAndPath("iconographic","font/mc5.json");
    static void draw(GuiGraphicsExtractor g,Font font,String raw,int x,int y,int maximumWidth) {
        String rank=raw.toUpperCase(Locale.ROOT);
        if(rank.isBlank() || rank.equals("DEFAULT") || rank.equals("NONE")) return;
        String label=rank.replace("_PLUS","+").replace('_',' ');
        int color=switch(rank) {
            case "VIP","VIP_PLUS" -> 0xFF55FF55;
            case "MVP","MVP_PLUS" -> 0xFF55FFFF;
            case "MVP_PLUS_PLUS" -> 0xFFFFAA00;
            case "ADMIN","OWNER","YOUTUBE" -> 0xFFFF5555;
            case "HELPER" -> 0xFF5555FF;
            case "MODERATOR","MOD" -> 0xFF00AA00;
            default -> ProfileStyle.accent();
        };
        boolean nativeFont=Minecraft.getInstance().getResourceManager().getResource(FONT_RESOURCE).isPresent();
        Component text=Component.literal(label).withColor(color & 0xFFFFFF);
        if(nativeFont) text=text.copy().withStyle(Style.EMPTY.withColor(color & 0xFFFFFF).withFont(new FontDescription.Resource(NATIVE_FONT)));
        int w=Math.min(maximumWidth,font.width(text)+12);
        if(g!=null && w>10) {
            Draw.roundedRect(g,x,y,w,15,3,Draw.lerp(ProfileStyle.input(),color,.10f));
            Draw.rect(g,x+3,y+4,1,7,color);
            g.text(font,text,x+7,y+3,0xFFFFFFFF,false);
        }
    }
}
