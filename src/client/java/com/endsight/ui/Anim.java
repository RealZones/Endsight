package com.endsight.ui;

/**
 * One float that chases a target, framerate-independent.
 *
 * Every hover fade, sliding knob and panel entrance in this package is one of
 * these. Kept deliberately tiny: the value is read during render, so it must cost
 * nothing and must never allocate.
 */
public final class Anim {

    private float value;
    private long lastMs = System.currentTimeMillis();

    public Anim(float initial) {
        this.value = initial;
    }

    /**
     * Step toward {@code target} and return the new value.
     *
     * The delta is capped: alt-tabbing away for a minute would otherwise come back
     * with dt so large that everything snaps, losing the animation entirely on the
     * one frame a person is actually looking at.
     */
    public float to(float target, float speed) {
        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastMs) / 1000f, 0.1f);
        lastMs = now;
        value += (target - value) * Math.min(1f, dt * speed);
        if (Math.abs(target - value) < 0.001f) value = target;
        return value;
    }

    public float value() {
        return value;
    }

    public void set(float v) {
        value = v;
    }
}
