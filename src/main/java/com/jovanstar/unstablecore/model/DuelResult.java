package com.jovanstar.unstablecore.model;

/** How a finished/forfeited duel was decided - used for history rows and win messages. */
public enum DuelResult {
    NORMAL_WIN,
    FORFEIT_WIN,
    TIMEOUT_WIN,
    TIMEOUT_NO_CONTEST
}
