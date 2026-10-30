package io.ruin.social.clan;

import com.google.gson.annotations.Expose;
import io.ruin.api.buffer.OutBuffer;
import io.ruin.api.filestore.utility.Huffman;
import io.ruin.api.utils.Random;
import io.ruin.api.utils.StringUtils;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.social.SocialList;
import io.ruin.social.SocialMember;
import io.ruin.social.SocialRank;
import io.ruin.utility.PlayerLookup;

import java.util.HashMap;

public class ClanChat extends ClanContainer {

    public SocialList parent;
    private String owner;
    @Expose private String ownerName;
    @Expose public String clanName;
    @Expose public SocialRank enterRank = null;
    @Expose public SocialRank talkRank = null;
    @Expose public SocialRank kickRank = SocialRank.CORPORAL;
    public ClanChat active;
    private boolean joining;
    private HashMap<String, Long> kicked;

    public void init(Player player) {
        this.sendSettings(player);
        if (this.ownerName != null) {
            this.join(player, this.ownerName);
        }
    }

    private void sendSettings(Player player) {
        String name = this.clanName == null ? "Chat disabled" : this.clanName;
        int enterRank = this.enterRank == null ? -1 : this.enterRank.id;
        int talkRank = this.talkRank == null ? -1 : this.talkRank.id;
        int kickRank = this.kickRank.id;
        OutBuffer out = new OutBuffer(2 + (name.length() + 1) + 3).sendVarBytePacket(86).addString(name).addByte(enterRank).addByte(talkRank).addByte(kickRank);
        player.getPacketSender().write(out);
    }

    public void update(boolean settingsOnly) {
        if (this.ccMembersCount == 0) {
            return;
        }
        for (int i = 0; i < this.ccMembersCount; ++i) {
            SocialMember member = this.ccMembers[i];
            Player pMember = World.getPlayer(member.playerName);
            if (pMember == null) continue;
            OutBuffer out = settingsOnly ? this.getSettingsBuffer() : this.getChannelBuffer();
            pMember.getPacketSender().write(out);
        }
    }

    public void disable() {
        for (int i = 0; i < this.ccMembersCount; ++i) {
            SocialMember member = this.ccMembers[i];
            Player pMember = World.getPlayer(member.playerName);
            if (pMember == null) continue;
            pMember.getClanChat().setActive(pMember, null);
            pMember.sendMessage("The clan chat channel you were in has been disabled.", 11);
        }
        if (this.kicked != null) {
            this.kicked.clear();
            this.kicked = null;
        }
    }

    public boolean isDisabled() {
        return this.clanName == null;
    }

    private void setActive(Player player, ClanChat newActive) {
        if (this.active == newActive) {
            return;
        }
        if (newActive == null) {
            this.active.removeClanMember(player.getName());
            player.getPacketSender().write(this.active.getLeaveBuffer());
            if (!this.active.isDisabled()) {
                this.active.update(false);
            }
        } else {
            this.ownerName = newActive.parent.ownerName;
            newActive.update(false);
        }
        this.active = newActive;
    }

    public void join(Player player, Object search) {
        if (this.joining) {
            player.sendMessage("You are already joining a channel, please wait...", 11);
            return;
        }
        player.sendMessage("Attempting to join channel...", 11);
        this.joining = true;
        PlayerLookup.forObj(search, entry -> {
            this.join(player, entry);
            this.joining = false;
        });
    }

    private void join(Player player, PlayerLookup.PlayerLookupEntry entry) {
        if (entry == null) {
            player.sendMessage("The channel you tried to join does not exist.", 11);
            return;
        }
        if (entry.playerName == null) {
            player.sendMessage("The channel you tried to join does not exist.", 11);
            return;
        }
        if (this.active != null) {
            player.sendMessage("You are already in a channel!", 11);
            return;
        }
        SocialList ownerList = SocialList.get(entry.playerName);
        if (ownerList.isIgnored(player.getName())) {
            player.sendMessage("You are blocked from joining this channel.");
            return;
        }
        ClanChat joinCc = ownerList.cc;
        joinCc.owner = entry.playerName;
        if (joinCc.addMember(player)) {
            this.setActive(player, joinCc);
            player.sendMessage("Now talking in clan chat channel " + joinCc.clanName + ".", 11);
            player.sendMessage("To talk, start each line of chat with the / symbol.", 11);
        }
    }

    public void leave(Player player, boolean logout) {
        if (this.active == null) {
            return;
        }
        this.ownerName = logout ? this.active.parent.ownerName : null;
        this.setActive(player, null);
    }

    public void kick(Player kickedBy, String kickName) {
        if (this.isDisabled()) {
            return;
        }
        SocialRank kickerRank = this.getRank(kickedBy);
        if (!ClanChat.meetsRank(this.kickRank, kickerRank)) {
            kickedBy.sendMessage("You are not a high enough rank to kick from this channel.", 11);
            return;
        }
        Player toKick = World.getPlayer(kickName);
        if (toKick == null || toKick.getActiveClanChat() != this) {
            kickedBy.sendMessage(kickName + " is not active in this channel.", 11);
            return;
        }
        SocialRank toKickRank = this.getRank(toKick);
        if (toKickRank == SocialRank.OWNER) {
            kickedBy.sendMessage("You can't kick this channel's owner!", 11);
            return;
        }
        if (!ClanChat.meetsRank(toKickRank, kickerRank)) {
            kickedBy.sendMessage("You are not a high enough rank to kick this member.", 11);
            return;
        }
        if (this.kicked == null) {
            this.kicked = new HashMap<>();
        }
        this.kicked.put(toKick.getName().toLowerCase(), System.currentTimeMillis() + 3600000L);
        toKick.getClanChat().leave(toKick, false);
        toKick.sendMessage("You have been kicked from the channel.", 11);
    }

    private boolean addMember(Player pMember) {
        Long kickedUntil;
        if (this.isDisabled()) {
            pMember.sendMessage("The channel you tried to join is currently disabled.", 11);
            return false;
        }
        String kickKey = pMember.getName().toLowerCase();
        if (this.kicked != null && (kickedUntil = this.kicked.get(kickKey)) != null) {
            if (kickedUntil > System.currentTimeMillis()) {
                pMember.sendMessage("You cannot join this channel because you are currently banned from it.", 11);
                return false;
            }
            this.kicked.remove(kickKey);
        }
        if (!ClanChat.meetsRank(this.enterRank, this.getRank(pMember))) {
            pMember.sendMessage("You are not a high enough rank to enter this channel.", 11);
            return false;
        }
        if (!this.addClanMember(new SocialMember(pMember.getName(), World.id))) {
            pMember.sendMessage("The channel you tried to join is full.", 11);
            return false;
        }
        return true;
    }

    public void message(Player sender, int rankId, String message) {
        if (this.isDisabled()) {
            return;
        }
        SocialRank senderRank = this.getRank(sender);
        if (!ClanChat.meetsRank(this.talkRank, senderRank)) {
            sender.sendMessage("You are not a high enough rank to talk in this channel.", 11);
            return;
        }
        message = StringUtils.fixCaps(message);
        for (int i = 0; i < this.ccMembersCount; ++i) {
            SocialMember member = this.ccMembers[i];
            Player pMember = World.getPlayer(member.playerName);
            if (pMember == null) continue;
            pMember.getPacketSender().write(this.getMessageBuffer(sender.getName(), rankId, message));
        }
    }

    private OutBuffer getMessageBuffer(String senderName, int rankId, String message) {
        OutBuffer out = new OutBuffer(255).sendVarBytePacket(22);
        out.addString(senderName);
        out.addString(this.clanName);
        for (int i = 0; i < 5; ++i) {
            out.addByte(Random.get(255));
        }
        out.addByte(rankId);
        Huffman.encrypt(out, message);
        return out;
    }

    private OutBuffer getBuffer(int type) {
        if (type == 0) {
            return new OutBuffer(3).sendVarShortPacket(48);
        }
        OutBuffer out = new OutBuffer(255).sendVarShortPacket(48).
                addString(this.owner).
                addString(this.clanName).
                addByte(ClanChat.getRankId(this.kickRank));
        if (type == 2) {
            out.addByte(255);
            return out;
        }
        out.addByte(this.ccMembersCount);
        for (int i = 0; i < this.ccMembersCount; ++i) {
            SocialMember member = this.ccMembers[i];
            out.addString(member.playerName);
            out.addShort(member.worldId);
            out.addByte(this.getRankId(member.playerName));
            out.addByte(0);
        }
        return out;
    }

    private OutBuffer getLeaveBuffer() {
        return this.getBuffer(0);
    }

    private OutBuffer getChannelBuffer() {
        return this.getBuffer(1);
    }

    private OutBuffer getSettingsBuffer() {
        return this.getBuffer(2);
    }

    private SocialRank getRank(Player player) {
        if (player.getName().equalsIgnoreCase(this.parent.ownerName)) {
            return SocialRank.OWNER;
        }
        if (player.isAdmin()) {
            return SocialRank.ADMIN;
        }
        SocialMember friend = this.parent.getFriend(player.getName());
        return friend == null ? null : friend.rank;
    }

    private int getRankId(String name) {
        if (name.equalsIgnoreCase(this.parent.ownerName)) {
            return SocialRank.OWNER.id;
        }
        Player player = World.getPlayer(name);
        if (player != null && player.isAdmin()) {
            return SocialRank.ADMIN.id;
        }
        SocialMember friend = this.parent.getFriend(name);
        return friend == null ? -1 : friend.rank.id;
    }

    private static int getRankId(SocialRank rank) {
        return rank == null ? -1 : rank.id;
    }

    private static boolean meetsRank(SocialRank reqRank, SocialRank checkRank) {
        return reqRank == null || checkRank != null && checkRank.id >= reqRank.id;
    }
}
