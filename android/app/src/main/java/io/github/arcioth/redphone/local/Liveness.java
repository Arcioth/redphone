package io.github.arcioth.redphone.local;

/** "Is the owning call session still the current one?" (the desktop worker's stop_ev, inverted). */
public interface Liveness {
    boolean alive();
}
