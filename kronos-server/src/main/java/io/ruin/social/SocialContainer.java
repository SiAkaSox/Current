package io.ruin.social;

import com.google.gson.annotations.Expose;

public class SocialContainer {

    @Expose protected int friendsCount;
    @Expose public SocialMember[] friends;
    @Expose protected int ignoresCount;
    @Expose public SocialMember[] ignores;

    public boolean addFriend(SocialMember friend) {
        if (this.friends == null) {
            this.friends = new SocialMember[400];
        }
        if (this.friendsCount >= this.friends.length || this.isFriend(friend.playerName)) {
            return false;
        }
        this.friends[this.friendsCount++] = friend;
        return true;
    }

    public String deleteFriend(String name) {
        for (int index = 0; index < this.friendsCount; ++index) {
            SocialMember friend = this.friends[index];
            if (!name.equalsIgnoreCase(friend.playerName)) continue;
            --this.friendsCount;
            for (int i = index; i < this.friendsCount; ++i) {
                this.friends[i] = this.friends[i + 1];
            }
            this.friends[this.friendsCount] = null;
            return friend.playerName;
        }
        return null;
    }

    public SocialMember getFriend(String name) {
        if (this.friends == null) {
            return null;
        }
        for (SocialMember friend : this.friends) {
            if (friend == null || !name.equalsIgnoreCase(friend.playerName)) continue;
            return friend;
        }
        return null;
    }

    public boolean isFriend(String name) {
        return this.getFriend(name) != null;
    }

    public void addIgnore(SocialMember ignore) {
        if (this.ignores == null) {
            this.ignores = new SocialMember[400];
        }
        if (this.ignoresCount >= this.ignores.length || this.isIgnored(ignore.playerName)) {
            return;
        }
        this.ignores[this.ignoresCount++] = ignore;
    }

    public String deleteIgnore(String name) {
        for (int index = 0; index < this.ignoresCount; ++index) {
            SocialMember ignore = this.ignores[index];
            if (!name.equalsIgnoreCase(ignore.playerName)) continue;
            --this.ignoresCount;
            for (int i = index; i < this.ignoresCount; ++i) {
                this.ignores[i] = this.ignores[i + 1];
            }
            this.ignores[this.ignoresCount] = null;
            return ignore.playerName;
        }
        return null;
    }

    public SocialMember getIgnore(String name) {
        if (this.ignores == null) {
            return null;
        }
        for (SocialMember ignore : this.ignores) {
            if (ignore == null || !name.equalsIgnoreCase(ignore.playerName)) continue;
            return ignore;
        }
        return null;
    }

    public boolean isIgnored(String name) {
        return this.getIgnore(name) != null;
    }
}
