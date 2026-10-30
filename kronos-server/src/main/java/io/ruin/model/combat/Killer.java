package io.ruin.model.combat;

import io.ruin.api.utils.Random;
import io.ruin.cache.Color;
import io.ruin.model.World;
import io.ruin.model.activities.tasks.DailyTask;
import io.ruin.model.activities.wilderness.ResourceArea;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.impl.SkillLamp;
import io.ruin.model.item.actions.impl.WildernessDeadmanKey;
import io.ruin.model.item.actions.impl.WildernessKey;
import io.ruin.model.item.actions.impl.boxes.WildernessRewardBox;
import io.ruin.model.map.Bounds;
import io.ruin.model.stat.StatType;
import io.ruin.services.discord.impl.KillingSpreeEmbedMessage;
import io.ruin.services.discord.impl.ShutdownEmbedMessage;
import io.ruin.utility.Broadcast;

public class Killer {
    public static final Bounds EDGEVILLE_FARM_SKIP_BOUNDS = new Bounds(3034, 3523, 3125, 3574, 0);

    public Player player;

    public int damage;

    public void reward(Player pKilled, boolean bhTarget) {
        /*
         * Chance to get Deadman key for deadman supply chest
         */
        if (Random.rollDie(30, 1)) {
            player.getInventory().addOrDrop(2399, 1);
            player.sendFilteredMessage("<col=6f0000>You gain a mysterious looking key.");
        }

        /*
         * Increment killer kills & spree.
         */
        Config.PVP_KILLS.increment(player, 1);
        int killerSpree = ++player.currentKillSpree;
        if(killerSpree > player.highestKillSpree)
            player.highestKillSpree = killerSpree;
        if(killerSpree > 1) {
            player.sendMessage("You are currently on a killing spree of " + killerSpree + "!");
            if(killerSpree % 5 == 0 || killerSpree > 15) {
                String spreeMessage = player.getName() + " is on a killing spree of " + killerSpree + ". Kill "
                        + (player.getAppearance().isMale() ? "him" : "her") + " to end the killing spree!";
                Broadcast.WORLD.sendPlain(KillingSpree.imgTag(killerSpree) + Color.DARK_GREEN.tag() + " " + spreeMessage);
                KillingSpreeEmbedMessage.sendDiscordMessage(spreeMessage);
            }
            if(player.getCombat().isSkulled()) //Overheads start at sprees of 2, so this fits here.
                player.getAppearance().setSkullIcon(KillingSpree.overheadId(player));
        }
        /*
         * Check for shutdown
         */
        int targetSpree = pKilled.currentKillSpree; //Spree is set to 0 in death method. (After this method)
        if(targetSpree >= 5) {
            String shutdownMessage = KillingSpree.shutdownMessage(player.getName(), pKilled.getName(), targetSpree);
            Broadcast.WORLD.sendPlain("<img=36> " + Color.DARK_GREEN.tag() + shutdownMessage);
            ShutdownEmbedMessage.sendDiscordMessage(shutdownMessage);
            if(targetSpree > player.highestShutdown)
                player.highestShutdown = targetSpree;
        }

        /*
         * Refill special attack
         */
        if (player.getPosition().isWithinDistance(pKilled.getPosition(), 32)) {
            // only restore special if they're within a 32 tile radius
            // this was orig being abused to refill special in different places of the world by getting alt accounts low
            // health and then killing them with an alt to receive the special restore
            player.getCombat().restoreSpecial(100);
        }

        /*
         * Roll for the wilderness reward box
         */
        int wildernessRewardBox = WildernessRewardBox.rollForDrop(player);
        if (wildernessRewardBox != -1) {
            player.getInventory().addOrDrop(new Item(wildernessRewardBox, 1));
        }

        /*
         * Roll for a wilderness key which can be exchanged for OSRS gold
         */
        if(World.wildernessKeyEvent) {
            WildernessKey.rollForPlayerKill(player, pKilled);
        }

        if (World.wildernessDeadmanKeyEvent) {
            WildernessDeadmanKey.rollForDeadmanKey(player, pKilled);
        }

        if (player.insideWildernessAgilityCourse) {
            player.getStats().addXp(StatType.Agility, 50000, false);
            player.getInventory().addOrDrop(11849, 10);
            player.sendFilteredMessage("<col=6f0000> You receive 50,000 agility experience and 10 marks of grace for killing a player inside the Agility course.");
        }

        if (player.getPosition().inBounds(ResourceArea.BOUNDS)) {
            player.getInventory().addOrDrop(SkillLamp.SKILL_CAMP, 1);
            player.sendFilteredMessage("<col=6f0000> You receive a skill lamp for killing a player inside the Resource Area.");
        }

        /*
         * Adjust wilderness elo
         */
        WildernessRating.adjustEloRatings(pKilled, player);

        /*
         * Check daily
         */
        DailyTask.checkPlayerKill(player, pKilled);
    }



}