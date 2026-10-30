package io.ruin.network.incoming.handlers;

import io.ruin.api.buffer.InBuffer;
import io.ruin.api.filestore.utility.Huffman;
import io.ruin.model.entity.player.Player;
import io.ruin.social.SocialList;
import io.ruin.social.SocialMember;
import io.ruin.social.SocialRank;
import io.ruin.social.clan.ClanChat;
import io.ruin.network.incoming.Incoming;
import io.ruin.services.Loggers;
import io.ruin.services.Punishment;
import io.ruin.utility.IdHolder;

@IdHolder(ids = {84, 80, 48, 56, 93, 67})
public class FriendsHandler implements Incoming {

    @Override
    public void handle(Player player, InBuffer in, int opcode) {
        String name;
        if(opcode == 67) {
            /**
             * Rank friend
             */
            int rank = in.readByte();
            name = in.readString();
            SocialRank socialRank = SocialRank.get(rank, null);
            if (socialRank == null) {
                return;
            }
            SocialMember friend = player.socialList.getFriend(name);
            if (friend == null || friend.rank == socialRank) {
                return;
            }
            friend.rank = socialRank;
            friend.resend();
            ClanChat cc = player.getClanChat();
            if (cc.inClan(friend.playerName)) {
                cc.update(false);
            }
            return;
        }
        name = in.readString();
        if(opcode == 80) {
            /**
             * Add friend
             */
            SocialList.handle(player, name, 1);
            return;
        }
        if(opcode == 48) {
            /**
             * Delete friend
             */
            SocialList.handle(player, name, 2);
            return;
        }
        if(opcode == 84) {
            /**
             * Add ignore
             */
            SocialList.handle(player, name, 3);
            return;
        }
        if(opcode == 56) {
            /**
             * Delete ignore
             */
            SocialList.handle(player, name, 4);
            return;
        }
        if(opcode == 93) {
            /**
             * Private message
             */
            String message = Huffman.decrypt(in, 100);
            if(Punishment.isMuted(player)) {
                if(player.shadowMute)
                    player.sendPM(name, message);
                else
                    player.sendMessage("You're muted and can't talk.");
                return;
            }
            SocialList.sendPrivateMessage(player, player.getClientGroupId(), name, message);
            Loggers.logPrivateChat(player.getName(), player.getIp(), name, message);
            return;
        }
    }

}
