package io.ruin.social;

import com.google.gson.annotations.Expose;
import io.ruin.model.entity.player.Player;
import io.ruin.utility.PlayerLookup;

import java.util.Objects;

public class SocialMember {

    @Expose public String playerName;
    @Expose public String previousPlayerName;
    protected boolean newName;
    @Expose public SocialRank rank;
    public int worldId = 0;

    public SocialMember(PlayerLookup.PlayerLookupEntry entry, SocialRank rank) {
        this.playerName = entry.playerName;
        this.previousPlayerName = "";
        this.rank = rank;
    }

    public SocialMember(String playerName, int worldId) {
        this.playerName = playerName;
        this.previousPlayerName = "";
        this.worldId = worldId;
    }

    public void resend() {
        this.worldId = 0;
    }

    protected void checkName(Player player) {
        if (!Objects.equals(this.playerName, player.getName())) {
            System.err.print(this.playerName);
            this.previousPlayerName = this.playerName;
            this.playerName = player.getName();
            this.newName = true;
        }
    }

    public boolean sendNewName() {
        if (this.newName) {
            this.newName = false;
            this.resend();
            return true;
        }
        return false;
    }
}
