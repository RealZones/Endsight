package com.endsight.profile;

import com.google.gson.*;

/** Privacy, quantities and wardrobe aliases are valuation boundaries, not UI details. */
public final class ProfileDataChecks {
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static JsonObject item(String id,int count) {
        return JsonParser.parseString("{\"entry\":\""+id+"\",\"count\":"+count+",\"item_data\":{\"item_name\":\"Item\"}}").getAsJsonObject();
    }
    private static JsonObject root() {
        return JsonParser.parseString("{\"player\":{\"player\":\"Tester\"},\"profile\":{\"id\":\"test\",\"inventory\":{},\"wardrobe\":{\"equipped\":1,\"slots\":{\"1\":{}}}}}").getAsJsonObject();
    }
    public static void main(String[] args) {
        JsonObject root=root(),p=root.getAsJsonObject("profile");
        p.getAsJsonObject("inventory").add("41",item("helmet",1));
        p.getAsJsonObject("inventory").add("0",item("fragment",64));
        p.getAsJsonObject("wardrobe").getAsJsonObject("slots").getAsJsonObject("1").add("helmet",item("helmet",1));
        ProfileData data=new ProfileData(root);
        require(data.assets.size()==2,"Equipped wardrobe armor must count once");
        require(data.container("wardrobe/1").slots().size()==1,"The wardrobe alias must remain browsable");
        p.getAsJsonObject("wardrobe").getAsJsonObject("slots").getAsJsonObject("1").remove("helmet");
        require(new ProfileData(root).container("wardrobe/1").slots().size()==1,"Equipped armor falls back to its inventory alias");
        JsonObject savedSet=p.getAsJsonObject("wardrobe").getAsJsonObject("slots").remove("1").getAsJsonObject();
        require(new ProfileData(root).container("wardrobe/1").slots().size()==1,"An omitted equipped set still renders inventory armor");
        p.getAsJsonObject("wardrobe").getAsJsonObject("slots").add("1",savedSet);
        p.getAsJsonObject("wardrobe").getAsJsonObject("slots").getAsJsonObject("1").add("helmet",item("helmet",1));
        require(data.container("inventory").slots().get(0).count()==64,"Stacks retain quantity");
        p.getAsJsonObject("inventory").remove("41");
        require(new ProfileData(root).assets.size()==2,"Missing inventory armor must not discard wardrobe gear");
        p.addProperty("inventory_hidden",true);
        data=new ProfileData(root);
        require(data.assets.isEmpty() && data.containers.isEmpty() && data.pets.isEmpty(),"Privacy overrides included container data");
        JsonObject catalog=JsonParser.parseString("{\"progression\":{\"default\":[50,175,375]}}").getAsJsonObject();
        require(ProfileData.level("mining",50,catalog).level()==1,"Skill thresholds are cumulative");
        require(ProfileData.level("mining",112.5,catalog).progress()==.5,"Partial level progress");
        require(ProfileData.level("mining",375,catalog).maxed(),"Maximum level is explicit");
        require(ProfileData.level("mining",10,new JsonObject()).level()==-1,"Missing curves cannot invent a level");
        JsonObject longCurve=new JsonObject(),progression=new JsonObject(); JsonArray steps=new JsonArray();
        for(int i=1;i<=100;i++) steps.add(i*10);
        progression.add("default",steps); longCurve.add("progression",progression);
        p.add("skills",JsonParser.parseString("{\"mining\":9999,\"combat\":9999}"));
        p.add("slayer",JsonParser.parseString("{\"zombie_level\":9,\"enderman_level\":9}"));
        require(new ProfileData(root).skillLevel("mining",longCurve).level()==50,"Shared XP curves do not override skill caps");
        require(new ProfileData(root).skillLevel("combat",longCurve).level()==60,"Combat cap includes both slayer unlocks");
        require(ProfileData.plain("§5Arcane").equals("Arcane"),"Profile names remove formatting");
        require(ProfileFormat.compact(1_500_000).equals("1.5M"),"Compact coin formatting");
        require(ProfileFormat.compact(6_036_639_049L).equals("6.04B"),"Coin formatting preserves useful precision");
        require(ProfileFormat.compact(Double.NaN).equals("—"),"Unknown numbers are not zero");
        require(ProfileMining.NODES.size()==46,"All scanned HotD nodes are loaded");
        java.util.Set<String> treeSlots=new java.util.HashSet<>();
        for(ProfileMining.Node node:ProfileMining.NODES) {
            require(node.tier()>=1 && node.tier()<=10 && node.column()>=1 && node.column()<=7,"HotD nodes stay inside the scanned grid");
            require(treeSlots.add(node.tier()+":"+node.column()),"HotD nodes do not overlap");
        }
        var coreNode=ProfileMining.NODES.stream().filter(n -> n.id().equals("CORE_OF_THE_DRAGON")).findFirst().orElseThrow();
        JsonObject hotd=new JsonObject();hotd.addProperty("core",10);
        require(ProfileMining.level(coreNode,hotd)==10,"Core uses its saved field outside the node map");
        JsonObject collectionRoot=root();
        JsonObject collectionCatalog=JsonParser.parseString("{\"items\":{\"ring\":{\"name\":\"Ring\",\"category\":\"ACCESSORY\",\"tier\":\"RARE\",\"accessory_family\":\"ender\"},\"artifact\":{\"name\":\"Artifact\",\"category\":\"ACCESSORY\",\"tier\":\"EPIC\",\"accessory_family\":\"ender\"}},\"pets\":{\"bal\":{\"display\":\"Bal\",\"rarities\":[\"EPIC\",\"LEGENDARY\"]}}}").getAsJsonObject();
        collectionRoot.getAsJsonObject("profile").getAsJsonObject("inventory").add("0",item("ring",1));
        ProfileData collectionData=new ProfileData(collectionRoot);
        ProfileCollection collection=new ProfileCollection(collectionData,collectionCatalog);
        require(collection.accessories.size()==1 && collection.missingAccessories.size()==1,"Only the next highest family goal is missing");
        require(collection.missingAccessories.getFirst().entry().equals("artifact"),"An owned lower tier does not hide the upgrade");
        require(collection.missingPets.size()==1 && collectionData.assets.size()==1,"Catalog suggestions never become owned assets");
        collectionRoot.getAsJsonObject("profile").getAsJsonObject("inventory").add("1",item("artifact",1));
        require(new ProfileCollection(new ProfileData(collectionRoot),collectionCatalog).missingAccessories.isEmpty(),"Owning the top family tier suppresses lower missing tiers");
        boolean rejected=false;
        try { new ProfileData(new JsonObject()); } catch(IllegalArgumentException expected) { rejected=true; }
        require(rejected,"A missing profile is an error state");
        System.out.println("Profile data checks passed.");
    }
}
