package com.endsight.qol;

/** Real message formats and timer lifecycle checks; no real-time waits. */
public final class GoopBaitReminderChecks {
    private static int checks;
    private static final String USED="The bait sinks in. Rift Obsidian Defenders and Watchers turn into Goop 50% more often for 10 minutes.";
    public static void main(String[] args) {
        var bait=new GoopBaitReminder();
        yes(bait.poll(1_000)==null,"Unknown buff must not alert");
        yes(bait.chat(USED,1_000),"Screenshot use message");
        yes(bait.poll(595_999)==null,"No alert before the final five seconds");
        for(int seconds=5;seconds>=0;seconds--) {
            var notice=bait.poll(601_000-seconds*1_000L);
            yes(notice!=null&&notice.seconds()==seconds&&notice.first()==(seconds==5),"Countdown numeral "+seconds);
            yes(bait.poll(601_001-seconds*1_000L)==null,"No duplicate countdown event "+seconds);
        }
        yes(bait.poll(602_000)==null,"One expiry per use");
        yes(bait.chat("§a"+USED.replace("10 minutes","§a10 minutes§r"),700_000),"Formatted server message");
        yes(bait.poll(1_294_999)==null&&expired(bait,1_300_000),"New bait rearms the reminder");
        bait.clear();bait.chat(USED,1_000);bait.chat(USED,301_000);
        yes(bait.poll(601_000)==null&&expired(bait,901_000),"Refreshing bait replaces the old deadline");
        bait.clear();
        yes(bait.tab("§aGoop Bait: §f9:50",1_000),"Screenshot timer");
        yes(bait.poll(585_999)==null&&expired(bait,591_000),"Join during an active buff");
        bait.clear();bait.tab("Goop Bait: 9:50",1_000);bait.tab("Goop Bait: 9:50",10_000);
        yes(expired(bait,591_000),"Repeated row cannot extend expiry");
        bait.tab("Goop Bait: 9:50",592_000);
        yes(bait.poll(592_000)==null,"Stale row cannot repeat an alert");
        bait.tab("Goop Bait: 10:00",600_000);
        yes(bait.poll(1_194_999)==null&&expired(bait,1_200_000),"Server-observed renewal rearms expiry");
        bait.clear();
        yes(bait.tab("Buffs\n  Goop Bait:\u00a09:50  \nCarrot: 2:00",1_000),"Multiline header/footer and nonbreaking space");
        yes(expired(bait,591_000),"Multiline timer deadline");
        bait.clear();bait.tab("Goop Bait: 0:00",1_000);
        yes(bait.poll(1_000)==null,"Inactive zero timer is not a bait use");
        bait.tab("Goop Bait: 0:01",2_000);bait.tab("Goop Bait: 0:00",3_000);
        yes(expired(bait,3_000)&&bait.poll(4_000)==null,"Explicit expiry alerts once");
        bait.clear();bait.chat(USED,1_000);bait.clear();
        yes(bait.poll(900_000)==null,"Disconnect clears old reminders");
        yes(bait.tab("Goop Bait: 9:50",901_000),"Reconnect can observe the same timer again");
        yes(expired(bait,1_491_000),"Reconnect gets a fresh deadline");
        for(String text:new String[]{"[MVP+] Player: "+USED,"Player whispers: "+USED,"Not "+USED,USED.replace("10 minutes","0 minutes"),"Goop Bait: 9:50"}) {
            bait.clear();yes(!bait.chat(text,1_000)&&bait.poll(900_000)==null,"Unrelated/spoofed chat: "+text);
        }
        for(String text:new String[]{"Player: Goop Bait: 9:50","Goop Bait: -1:00","Goop Bait: 1:60","Goop Bait: 1:3","Carrot: 9:50","Goop Bait: 9:50 and more"}) {
            bait.clear();yes(!bait.tab(text,1_000)&&bait.poll(900_000)==null,"Invalid/unrelated timer: "+text);
        }
        bait.clear();bait.tab("Goop Bait: 0:03",1_000);
        var late=bait.poll(1_000);
        yes(late!=null&&late.seconds()==3&&late.first(),"Late observation starts at its actual remaining time");
        bait.tab("Goop Bait: 0:04",1_500);
        yes(bait.poll(1_500)==null,"Rounding cannot run the countdown backwards");
        yes(expired(bait,9_000)&&bait.poll(9_001)==null,"A stalled client expires once without replaying digits");
        System.out.println("Goop Bait reminder checks passed: "+checks);
    }
    private static void yes(boolean value,String why) { checks++;if(!value)throw new AssertionError(why); }
    private static boolean expired(GoopBaitReminder bait,long now) {
        var notice=bait.poll(now);return notice!=null&&notice.seconds()==0;
    }
}
