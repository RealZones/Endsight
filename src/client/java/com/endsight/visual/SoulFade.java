package com.endsight.visual;

/**
 * The opacity {@link Summons} wants for one body, carried on its render state. The state
 * is all the renderer has by the time it draws; the entity is gone by then.
 */
public interface SoulFade {

    /** 0 to 1, or -1 when the body is drawn as it normally is. */
    float endsight$soulAlpha();

    void endsight$setSoulAlpha(float alpha);
}
