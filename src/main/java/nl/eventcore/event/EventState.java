package nl.eventcore.event;

public enum EventState {
    /** Er draait niets. */
    IDLE,
    /** Lobby is open, spelers kunnen joinen. */
    LOBBY,
    /** Genoeg spelers, de start-countdown loopt. */
    COUNTDOWN,
    /** Het event is bezig. */
    RUNNING,
    /** Het event wordt afgesloten. */
    ENDING
}
