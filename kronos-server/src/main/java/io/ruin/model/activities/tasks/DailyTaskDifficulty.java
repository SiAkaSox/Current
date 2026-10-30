package io.ruin.model.activities.tasks;

public enum DailyTaskDifficulty {
    EASY(1),
    MEDIUM(2),
    HARD(3);

    private int points;

    DailyTaskDifficulty(int points) {
        this.points = points;
    }

    public int getPointsReward() {
        return points;
    }
}
