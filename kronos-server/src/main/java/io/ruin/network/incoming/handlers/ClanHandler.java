package io.ruin.network.incoming.handlers;

import io.ruin.api.buffer.InBuffer;
import io.ruin.model.entity.player.Player;
import io.ruin.social.clan.ClanChat;
import io.ruin.network.incoming.Incoming;
import io.ruin.utility.IdHolder;

@IdHolder(ids = {53, 22})
public class ClanHandler implements Incoming {

    @Override
    public void handle(Player player, InBuffer in, int opcode) {
        String username = in.readString();
        if(opcode == 53) {
            /**
             * Join / Leave
             */
            if (username.isEmpty()) {
                player.getClanChat().leave(player, false);
            } else {
                player.getClanChat().join(player, username);
            }
            return;
        }
        if(opcode == 22) {
            /**
             * Kick
             */
            ClanChat active = player.getActiveClanChat();
            if (active != null) {
                active.kick(player, username);
            }
            return;
        }
    }

}
