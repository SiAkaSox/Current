package io.ruin.social.clan;

import io.ruin.social.SocialMember;

public class ClanContainer {
    public int ccMembersCount;
    public SocialMember[] ccMembers;

    public boolean addClanMember(SocialMember member) {
        if (this.ccMembers == null) {
            this.ccMembers = new SocialMember[100];
        }
        if (this.ccMembersCount >= this.ccMembers.length || this.inClan(member.playerName)) {
            return false;
        }
        this.ccMembers[this.ccMembersCount++] = member;
        return true;
    }

    public void removeClanMember(String name) {
        for (int index = 0; index < this.ccMembersCount; ++index) {
            SocialMember member = this.ccMembers[index];
            if (!name.equalsIgnoreCase(member.playerName)) continue;
            --this.ccMembersCount;
            for (int i = index; i < this.ccMembersCount; ++i) {
                this.ccMembers[i] = this.ccMembers[i + 1];
            }
            this.ccMembers[this.ccMembersCount] = null;
            return;
        }
    }

    public SocialMember getClanMember(String name) {
        if (this.ccMembers == null) {
            return null;
        }
        for (SocialMember member : this.ccMembers) {
            if (member == null || !name.equalsIgnoreCase(member.playerName)) continue;
            return member;
        }
        return null;
    }

    public boolean inClan(String name) {
        return this.getClanMember(name) != null;
    }
}
