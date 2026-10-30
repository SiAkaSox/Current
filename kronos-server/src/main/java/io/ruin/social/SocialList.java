package io.ruin.social;

import com.google.gson.annotations.Expose;
import io.ruin.Server;
import io.ruin.api.utils.JsonUtils;
import io.ruin.api.utils.StringUtils;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.social.clan.ClanChat;
import io.ruin.utility.PlayerLookup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;

public class SocialList extends SocialContainer {

    public String ownerName;
    private boolean sent;
    @Expose public int privacy;
    @Expose public ClanChat cc;
    private static final HashMap<String, SocialList> LOADED = new HashMap<>();
    private static final File social_folder = new File(io.ruin.api.utils.ServerPaths.getSocial());

    public void offline(Player player) {
        this.sent = false;
        notifyFriends(this.ownerName, false);
        this.cc.leave(player, true);
        SocialList.save(this);
    }

    public void process(Player player) {
        if (!this.sent) {
            this.sent = true;
            this.update(null);
            player.sendSocial(true, this.friends);
            player.sendSocial(false, this.ignores);
            player.sendPrivacy(this.privacy);
            this.cc.init(player);
            notifyFriends(this.ownerName, true);
        }
    }

    public static void notifyFriends(String name, boolean online) {
        SocialList playerList = LOADED.get(name.toLowerCase());
        for (SocialList list : LOADED.values()) {
            if (list.ownerName.equalsIgnoreCase(name)) continue;
            if (list.friends == null) continue;
            for (SocialMember friend : list.friends) {
                if (friend == null) continue;
                if (!friend.playerName.equalsIgnoreCase(name)) continue;
                boolean hidden = false;
                if (playerList != null) {
                    hidden = playerList.privacy == 2
                            || playerList.privacy == 1 && !playerList.isFriend(list.ownerName)
                            || playerList.isIgnored(list.ownerName);
                }
                int newWorldId = hidden ? 0 : (online ? World.id : 0);
                if (friend.worldId == newWorldId) break;
                friend.worldId = newWorldId;
                Player owner = World.getPlayer(list.ownerName);
                if (owner != null) {
                    owner.sendSocial(true, friend);
                }
                break;
            }
        }
    }

    private void update(Player sendFor) {
        if (this.friends != null) {
            for (SocialMember friend : this.friends) {
                if (friend == null) continue;
                int updatedWorldId = 0;
                Player pFriend = World.getPlayer(friend.playerName);
                if (pFriend != null) {
                    friend.checkName(pFriend);
                    SocialList fList = SocialList.get(friend.playerName);
                    boolean hidden = fList.privacy == 2 || fList.privacy == 1 && !fList.isFriend(this.ownerName) || fList.isIgnored(this.ownerName);
                    if (!hidden) {
                        updatedWorldId = World.id;
                    }
                }
                if (friend.worldId == updatedWorldId && !friend.newName) continue;
                friend.worldId = updatedWorldId;
                if (sendFor == null) continue;
                sendFor.sendSocial(true, friend);
            }
        }
        if (this.ignores != null) {
            for (SocialMember ignore : this.ignores) {
                if (ignore == null) continue;
                Player pIgnore = World.getPlayer(ignore.playerName);
                if (pIgnore != null) {
                    ignore.checkName(pIgnore);
                }
                if (ignore.worldId == 0 && !ignore.newName) continue;
                ignore.worldId = 0;
                if (sendFor == null) continue;
                sendFor.sendSocial(false, ignore);
            }
        }
    }

    private void add(Player player, String name, boolean ignore) {
        String type = ignore ? "ignore" : "friend";
        PlayerLookup.forName(name, entry -> {
                    if (entry == null) {
                        player.sendMessage("Unable to add " + type + " - unknown player.");
                        return;
                    }
                    if (entry.playerName == null) {
                        player.sendMessage("Unable to add " + type + " - unknown player.");
                        return;
                    }
                    SocialMember member = new SocialMember(entry, ignore ? null : SocialRank.FRIEND);
                    if (ignore) {
                        this.addIgnore(member);
                        player.sendSocial(false, member);
                    } else if (this.addFriend(member)) {
                        Player pFriend = World.getPlayer(member.playerName);
                        if (pFriend != null) {
                            SocialList fList = SocialList.get(member.playerName);
                            boolean hidden = fList.privacy == 2
                                    || fList.privacy == 1 && !fList.isFriend(this.ownerName)
                                    || fList.isIgnored(this.ownerName);
                            if (!hidden) {
                                member.worldId = World.id;
                            }
                        }
                        player.sendSocial(true, member);
                        if (this.cc.inClan(member.playerName)) {
                            this.cc.update(false);
                        }
                    }
                }
        );
    }

    private void delete(String name) {
        String deletedName = this.deleteFriend(name);
        if (deletedName != null && this.cc.inClan(deletedName)) {
            this.cc.update(false);
        }
    }

    public static void handle(Player player, String name, int requestType) {
        if (requestType == 1) {
            player.socialList.add(player, name, false);
        } else if (requestType == 2) {
            player.socialList.delete(name);
        } else if (requestType == 3) {
            player.socialList.add(player, name, true);
        } else if (requestType == 4) {
            player.socialList.deleteIgnore(name);
        }
    }

    public static void sendPrivateMessage(Player player, int rankId, String name, String message) {
        Player receiver = World.getPlayer(name);
        if (receiver == null) {
            return;
        }
        message = StringUtils.fixCaps(message);
        player.sendPM(name, message);
        receiver.sendReceivePM(player.getName(), rankId, message);
    }

    public static SocialList get(String name) {
        String key = name.toLowerCase();
        SocialList loaded = LOADED.get(key);
        if (loaded == null) {
            File file = new File(social_folder, key + ".json");
            if (file.exists()) {
                try {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    String json = new String(bytes);
                    loaded = (SocialList) JsonUtils.GSON_EXPOSE.fromJson(json, SocialList.class);
                } catch (Exception e) {
                    Server.logError("Failed to load social list for " + name, e);
                }
            }
            if (loaded == null) {
                loaded = new SocialList();
            }
        }
        if (loaded.cc == null) {
            loaded.cc = new ClanChat();
        }
        loaded.cc.parent = loaded;
        loaded.ownerName = name;
        LOADED.put(key, loaded);
        if (loaded.friends != null) {
            for (SocialMember friend : loaded.friends) {
                if (friend != null) {
                    PlayerLookup.register(friend.playerName);
                }
            }
        }
        if (loaded.ignores != null) {
            for (SocialMember ignore : loaded.ignores) {
                if (ignore != null) {
                    PlayerLookup.register(ignore.playerName);
                }
            }
        }
        return loaded;
    }

    private static void save(SocialList list) {
        SocialMember[] origFriends = list.friends;
        SocialMember[] origIgnores = list.ignores;
        if (list.friends != null) {
            list.friends = Arrays.copyOf(list.friends, list.friendsCount);
        }
        if (list.ignores != null) {
            list.ignores = Arrays.copyOf(list.ignores, list.ignoresCount);
        }
        try {
            String json = JsonUtils.GSON_EXPOSE.toJson(list);
            if(!social_folder.exists() && !social_folder.mkdirs())
                throw new IOException("social directory could not be created!");
            Files.write(new File(social_folder, list.ownerName.toLowerCase() + ".json").toPath(), json.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception e) {
            Server.logError("Failed to save social list for " + list.ownerName, e);
        } finally {
            list.friends = origFriends;
            list.ignores = origIgnores;
        }
    }
}
