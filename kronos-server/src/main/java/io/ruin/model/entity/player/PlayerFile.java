package io.ruin.model.entity.player;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.api.process.ProcessFactory;
import io.ruin.api.protocol.Protocol;
import io.ruin.api.protocol.Response;
import io.ruin.api.utils.*;
import io.ruin.model.World;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.skills.construction.RoomDefinition;
import io.ruin.model.skills.construction.room.Room;
import org.mindrot.jbcrypt.BCrypt;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerFile {

    private static final File CHARACTERS_FOLDER = new File(io.ruin.api.utils.ServerPaths.getCharacters());
    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(new ProcessFactory("save-worker", Thread.NORM_PRIORITY - 1));
    private static final Gson GSON_LOADER = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().registerTypeAdapter(Room.class, new JsonDeserializer<Room>() {

        @Override
        public Room deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            JsonObject obj = jsonElement.getAsJsonObject();
            RoomDefinition definition = RoomDefinition.valueOf(obj.get("definition").getAsString());
            return jsonDeserializationContext.deserialize(jsonElement, definition.getHandler());
        }
    }).create();

    public static void loadPlayer(PlayerLogin login) {
        if (Protocol.method360(login.info.name) == null) {
            login.deny(Response.CHANGE_DISPLAY_NAME);
            return;
        }
        if (!World.isDev()) {
            if (UUIDBan.isUUIDBanned(login.info.uuid)
                    || IPBans.isIPBanned(login.info.ipAddress)
                    || MACBan.isMACBanned(login.info.macAddress)) {
                login.deny(Response.DISABLED_ACCOUNT);
                return;
            }
        }
        String saved = "";
        File savedFile = new File(CHARACTERS_FOLDER, login.info.name.toLowerCase() + ".json");
        if (savedFile.exists()) {
            try {
                saved = new String(Files.readAllBytes(savedFile.toPath()));
            } catch (Exception e) {
                Server.logError("Failed to load player file for " + login.info.name, e);
                login.deny(Response.ERROR_LOADING_ACCOUNT);
                return;
            }
        }
        if (World.isDev()) {
            login.info.update(login.info.name, saved, ListUtils.toList(PlayerGroup.ADMINISTRATOR.id));
        } else {
            login.info.update(login.info.name, saved, ListUtils.toList(PlayerGroup.REGISTERED.id));
        }
        login.success();
    }

    public static Player load(PlayerLogin login) {
        try {
            Player player;
            if (login.info.saved == null || login.info.saved.isEmpty())
                player = new Player();
            else
                player = GSON_LOADER.fromJson(login.info.saved, Player.class);
            Config.load(player);

            if (player.getSavedGroupIds() != null && player.getSavedGroupIds().contains(PlayerGroup.BANNED.id)) {
                login.deny(Response.DISABLED_ACCOUNT);
                return null;
            }
            if (!World.isDev() && player.getPassword() != null) {
                boolean passwordMatch;
                if (player.getPassword().startsWith("$2a$")) {
                    passwordMatch = BCrypt.checkpw(login.info.password, player.getPassword());
                } else {
                    passwordMatch = login.info.password.equals(player.getPassword());
                }
                if (!passwordMatch) {
                    login.deny(Response.INVALID_LOGIN);
                    return null;
                }
            }

            return player;
        } catch (Throwable t) {
            Server.logError("", t);
            return null;
        }
    }

    public static void save(Player player, int logoutAttempt) {
        SAVE_EXECUTOR.execute(() -> {
            Config.save(player);
            String json;
            try {
                json = JsonUtils.GSON_EXPOSE.toJson(player);
            } catch (Exception e) {
                Server.logError("", e);
                return;
            }
            try {
                if (!CHARACTERS_FOLDER.exists() && !CHARACTERS_FOLDER.mkdirs())
                    throw new IOException("characters directory could not be created!");
                Files.write(new File(CHARACTERS_FOLDER, player.getName().toLowerCase() + ".json").toPath(), json.getBytes(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException e) {
                Server.logError("Failed to save player " + player.getName(), e);
            } finally {
                if (logoutAttempt != -1)
                    player.finishLogout(logoutAttempt);
            }
        });
    }
}
