package com.endsight.qol;

import java.util.Random;

/** Timing boundaries use the real cadence and a monotonic clock, without Minecraft. */
public final class ClickCadenceChecks {
    private static final long CAP = 105_263_158L;
    public static void main(String[] args) {
        firstDelayAndReset(); independentConsumers(); liveSettings(); invalidSettings();
        noCatchUp(); randomBranches(); nanosecondClockWrap(); sampledHardCap();
        System.out.println("Click cadence checks passed: hard 9.5 CPS cap including jitter and burst intervals");
    }

    private static void firstDelayAndReset() {
        ClickCadence cadence = fixed(9.5,9.5);
        check(!cadence.poll(0), "initial physical press is not duplicated");
        check(!cadence.poll(CAP-1), "full capped interval is required");
        check(cadence.poll(CAP), "click is due at the nanosecond ceiling");
        check(!cadence.poll(CAP), "same frame cannot emit twice");
        cadence.reset();
        check(!cadence.poll(ms(10_000)), "new hold does not replay overdue clicks");
        check(!cadence.poll(ms(10_000)+CAP-1), "new hold waits a full capped interval");
        check(cadence.poll(ms(10_000)+CAP), "reset hold resumes on time");
    }

    private static void independentConsumers() {
        ClickCadence a=fixed(9.5,9.5), b=fixed(9.5,9.5);
        a.poll(0); b.poll(0);
        check(a.poll(CAP) && b.poll(CAP), "consumers do not steal each other's scheduled clicks");
        a.reset();
        check(b.poll(2*CAP), "reset leaves the other cadence intact");
        check(!a.poll(2*CAP), "reset cadence still waits");
    }

    private static void liveSettings() {
        double[] range={6,6};
        ClickCadence cadence=new ClickCadence(() -> range[0], () -> range[1], new FixedRandom());
        cadence.poll(0);
        range[0]=range[1]=100;
        check(!cadence.poll(ms(10)), "raising settings cannot issue an immediate click");
        check(!cadence.poll(ms(10)+CAP-1), "oversized new rate still waits the hard interval");
        check(cadence.poll(ms(10)+CAP), "new rate is capped during the existing hold");
        long changed=ms(11)+CAP;
        range[0]=range[1]=1;
        check(!cadence.poll(changed), "lower rate replaces the pending fast deadline");
        check(!cadence.poll(changed+ms(1_000)-1), "slower interval waits completely");
        check(cadence.poll(changed+ms(1_000)), "slower live setting takes effect");
    }

    private static void invalidSettings() {
        boundary(fixed(100,Double.POSITIVE_INFINITY),CAP,"oversized supplier");
        boundary(fixed(-100,Double.NEGATIVE_INFINITY),ms(1_000),"nonpositive supplier");
        boundary(fixed(Double.NaN,Double.NaN),133_333_333L,"NaN defaults");
        boundary(fixed(9.5,1),190_476_190L,"reversed suppliers");
    }

    private static void noCatchUp() {
        ClickCadence cadence=fixed(9.5,9.5);
        cadence.poll(0);
        check(cadence.poll(ms(60_000)), "late frame emits one click");
        for(int i=0;i<100;i++)check(!cadence.poll(ms(60_000)), "no backlog replay");
        check(!cadence.poll(ms(60_000)+CAP-1), "late frame starts a fresh capped interval");
        check(cadence.poll(ms(60_000)+CAP), "schedule resumes from actual emission");
    }

    private static void randomBranches() {
        boundary(new ClickCadence(() -> 9.5, () -> 9.5, new FixedRandom(.5,.03,.5)),CAP,
                "former 55-85ms burst is capped");
        boundary(new ClickCadence(() -> 9.5, () -> 9.5, new FixedRandom() {
            @Override public double nextGaussian() { return -100; }
        }),CAP,"short normal jitter is capped");
        long hitch=(long)((1000.0/9.5+225)*1_000_000);
        boundary(new ClickCadence(() -> 9.5, () -> 9.5, new FixedRandom(.5,.001,.5)),hitch,
                "long pauses remain longer than the cap");
    }

    private static void nanosecondClockWrap() {
        ClickCadence cadence=fixed(9.5,9.5);
        long start=Long.MAX_VALUE-ms(50);
        check(!cadence.poll(start), "arm near signed clock wrap");
        check(!cadence.poll(start+CAP-1), "wrapped deadline cannot fire early");
        check(cadence.poll(start+CAP), "wrapped deadline fires on time");
    }

    private static void sampledHardCap() {
        for(long seed:new long[]{7,42,8913}) {
            ClickCadence cadence=new ClickCadence(() -> 100, () -> Double.POSITIVE_INFINITY,new Random(seed));
            long last=0; int clicks=0;
            for(long now=0;now<=ms(120_000);now+=ms(1)) if(cadence.poll(now)) {
                check(now-last>=CAP,"random drift/jitter emitted faster than 9.5 CPS");
                check(!cadence.poll(now),"randomized cadence duplicated a frame");
                last=now;clicks++;
            }
            check(clicks>0 && clicks<=1140,"two-minute output exceeds 9.5 CPS");
        }
    }

    private static void boundary(ClickCadence cadence,long gap,String label) {
        check(!cadence.poll(0),label+": arms");
        check(!cadence.poll(gap-1),label+": waits");
        check(cadence.poll(gap),label+": due");
    }
    private static ClickCadence fixed(double min,double max) {
        return new ClickCadence(() -> min, () -> max,new FixedRandom());
    }
    private static long ms(long value) { return value*1_000_000; }
    private static void check(boolean condition,String label) { if(!condition)throw new AssertionError(label); }
    private static class FixedRandom extends Random {
        private final double[] samples; private int position;
        FixedRandom(double... samples) { this.samples=samples; }
        @Override public double nextDouble() { return position<samples.length?samples[position++]:.5; }
        @Override public int nextInt(int bound) { return 0; }
        @Override public double nextGaussian() { return 0; }
    }
}
