package com.endsight.profile;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.endsight.profile.ProfileData.*;

/** Slot positions and caps transcribed from the owner's saved in-game HotD scan. */
public final class ProfileMining {
    public record Node(String id,String name,int tier,int column,int maximum,boolean ability) { }
    public static final List<Node> NODES=load();
    private static List<Node> load() {
        try(var stream=ProfileMining.class.getResourceAsStream("/endsight-hotd-tree.json")) {
            if(stream==null) return List.of();
            JsonArray rows=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonArray();
            List<Node> nodes=new ArrayList<>();
            for(JsonElement row:rows) {
                JsonObject n=row.getAsJsonObject();
                nodes.add(new Node(text(n,"id",""),text(n,"name",""),integer(n,"tier",0),integer(n,"column",0),integer(n,"maximum",1),bool(n,"ability")));
            }
            return List.copyOf(nodes);
        } catch(IOException | RuntimeException error) { return List.of(); }
    }
    public static int level(Node node,JsonObject hotd) {
        return node.id().equals("CORE_OF_THE_DRAGON") ? integer(hotd,"core",0) : integer(object(hotd,"nodes"),node.id(),0);
    }
}
