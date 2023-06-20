package io.icker.factions.config;

import com.google.gson.annotations.SerializedName;

public class PowerConfig {
    @SerializedName("base")
    public int BASE = 20;

    @SerializedName("member")
    public int MEMBER = 20;

    @SerializedName("claimWeight")
    public int CLAIM_WEIGHT = 0;

    @SerializedName("deathPenalty")
    public int DEATH_PENALTY = 10;

    @SerializedName("powerTicks")
    public PowerTicks POWER_TICKS = new PowerTicks();

    public static class PowerTicks {
        @SerializedName("ticks")
        public int TICKS = 12000;

        @SerializedName("reward")
        public int REWARD = 0;
    }
}
