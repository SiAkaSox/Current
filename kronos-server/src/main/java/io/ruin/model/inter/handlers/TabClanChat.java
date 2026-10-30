package io.ruin.model.inter.handlers;

import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.actions.OptionAction;
import io.ruin.model.inter.actions.SimpleAction;
import io.ruin.social.SocialRank;
import io.ruin.social.clan.ClanChat;

public class TabClanChat {

    static {
        InterfaceHandler.register(Interface.CLAN_CHAT, h -> h.actions[24] = (SimpleAction) p -> p.openInterface(InterfaceType.MAIN, Interface.CLAN_CHAT_SETTINGS));
        InterfaceHandler.register(Interface.CLAN_CHAT_SETTINGS, h -> {
            h.actions[10] = (OptionAction) (player, option) -> {
                if(option == 1) {
                    player.nameInput("Enter chat prefix:", name -> {
                        name = name.replaceAll("[^a-zA-Z0-9\\s]", "");
                        name = name.substring(0, Math.min(name.length(), 12));
                        if(name.isEmpty()) {
                            player.retryStringInput("Invalid chat prefix, try again:");
                            return;
                        }
                        player.getPacketSender().sendString(Interface.CLAN_CHAT_SETTINGS, 10, name);
                        ClanChat cc = player.getClanChat();
                        cc.clanName = name;
                        cc.update(true);
                    });
                    return;
                }
                player.getPacketSender().sendString(Interface.CLAN_CHAT_SETTINGS, 10, "Chat disabled");
                ClanChat cc = player.getClanChat();
                cc.clanName = null;
                cc.disable();
            };
            h.actions[13] = (OptionAction) (player, option) -> {
                ClanChat cc = player.getClanChat();
                cc.enterRank = SocialRank.get(option - 2, null);
            };
            h.actions[16] = (OptionAction) (player, option) -> {
                ClanChat cc = player.getClanChat();
                cc.talkRank = SocialRank.get(option - 2, null);
            };
            h.actions[19] = (OptionAction) (player, option) -> {
                ClanChat cc = player.getClanChat();
                cc.kickRank = SocialRank.get(option - 2, SocialRank.CORPORAL);
                cc.update(true);
            };
        });
    }

}
