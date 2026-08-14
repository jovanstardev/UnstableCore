package com.jovanstar.unstablecore.leaderboard;

import java.util.UUID;

public record LeaderboardEntry(int rank, UUID uuid, String name, double value) {
}
