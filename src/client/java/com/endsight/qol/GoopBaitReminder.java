package com.endsight.qol;

import java.util.regex.Pattern;

/** A final five-second alert and one expiry notice, without a permanent HUD timer. */
public final class GoopBaitReminder {
    public record Notice(int seconds,boolean first) { }
    private static final Pattern COLOURS=Pattern.compile("§[0-9A-FK-ORa-fk-or]");
    private static final Pattern USED=Pattern.compile(
            "^The bait sinks in\\.\\s+Rift Obsidian Defenders and Watchers turn into Goop\\s+50% more often for\\s+(\\d{1,3}) minutes?\\.$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMAINING=Pattern.compile(
            "^\\h*Goop\\h+Bait\\h*:\\h*(\\d{1,3}):([0-5]\\d)\\h*$",Pattern.CASE_INSENSITIVE|Pattern.MULTILINE);
    private long deadline=-1;
    private int lastTabSeconds=-1;
    private int lastNotice=-1;

    private static String clean(String text) {
        return text==null ? "" : COLOURS.matcher(text).replaceAll("").replace('\u00A0',' ').strip();
    }

    public boolean chat(String text,long now) {
        var match=USED.matcher(clean(text));
        if(!match.matches()) return false;
        int minutes=Integer.parseInt(match.group(1));
        if(minutes==0) return false;
        deadline=now+minutes*60_000L;
        lastTabSeconds=-1;
        lastNotice=-1;
        return true;
    }

    public boolean tab(String text,long now) {
        var match=REMAINING.matcher(clean(text));
        if(!match.find()) return false;
        int seconds=Integer.parseInt(match.group(1))*60+Integer.parseInt(match.group(2));
        // Repeated copies of a tab row must not keep extending the same deadline.
        // A fresh use message or reconnect clears this observation.
        if(seconds==lastTabSeconds) return true;
        lastTabSeconds=seconds;
        if(seconds==0 && deadline<0) return true;
        long next=now+seconds*1_000L;
        // A materially longer server timer is a refreshed buff. Normal one-second
        // display rounding must not rearm an already delivered expiry notification.
        if(deadline<0 || next>deadline+3_000) lastNotice=-1;
        deadline=next;
        return true;
    }

    public Notice poll(long now) {
        if(deadline<0 || lastNotice==0) return null;
        int seconds=(int)Math.max(0,(deadline-now+999)/1_000);
        if(seconds>5 || lastNotice>=0 && seconds>=lastNotice) return null;
        Notice result=new Notice(seconds,lastNotice<0);
        lastNotice=seconds;
        return result;
    }

    public boolean countingDown(long now) {
        return deadline>now && deadline-now<=5_000;
    }

    public void clear() {
        deadline=-1;
        lastTabSeconds=-1;
        lastNotice=-1;
    }
}
