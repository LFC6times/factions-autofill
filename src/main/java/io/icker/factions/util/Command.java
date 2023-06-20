package io.icker.factions.util;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.icker.factions.FactionsMod;
import io.icker.factions.api.persistents.Faction;
import io.icker.factions.api.persistents.User;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.UserCache;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;


public interface Command {
    LiteralCommandNode<ServerCommandSource> getNode();
    boolean permissions = FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0");

    interface Requires {
        boolean run(User user);

        @SafeVarargs
        static Predicate<ServerCommandSource> multiple(Predicate<ServerCommandSource>... args) {
            return source -> {
                for (Predicate<ServerCommandSource> predicate : args) {
                    if (!predicate.test(source)) return false;
                }

                return true;
            };
        }

        static Predicate<ServerCommandSource> isFactionless() {
            return require(user -> !user.isInFaction());
        }

        static Predicate<ServerCommandSource> isMember() {
            return require(user -> user.isInFaction());
        }

        static Predicate<ServerCommandSource> isCommander() {
            return require(user -> user.rank == User.Rank.COMMANDER || user.rank == User.Rank.LEADER || user.rank == User.Rank.OWNER);
        }

        static Predicate<ServerCommandSource> isLeader() {
            return require(user -> user.rank == User.Rank.LEADER || user.rank == User.Rank.OWNER);
        }

        static Predicate<ServerCommandSource> isOwner() {
            return require(user -> user.rank == User.Rank.OWNER);
        }
        
        static Predicate<ServerCommandSource> isAdmin() {
            return source -> source.hasPermissionLevel(FactionsMod.CONFIG.REQUIRED_BYPASS_LEVEL);
        }

        static Predicate<ServerCommandSource> hasPerms(String permission, int defaultValue) {
            return source -> !permissions || Permissions.check(source, permission, defaultValue);
        }

        static Predicate<ServerCommandSource> require(Requires req) {
            return source -> {
                ServerPlayerEntity entity = source.getPlayer();
                User user = Command.getUser(entity);
                return req.run(user);
            };
        }
    }

    interface Suggests {
        String[] run(User user);

        static SuggestionProvider<ServerCommandSource> allFactions() {
            return allFactions(true);
        }

        static SuggestionProvider<ServerCommandSource> allFactions(boolean includeYou) {
            return suggest(user -> 
                Faction.all()
                    .stream()
                    .filter(f -> includeYou || !user.isInFaction() || !user.getFaction().getID().equals(f.getID()))
                    .map(Faction::getName)
                    .toArray(String[]::new)
            );
        }

        static SuggestionProvider<ServerCommandSource> allPlayers() {
            return (context, builder) -> {
                UserCache cache = context.getSource().getServer().getUserCache();

                for (User user : User.all()) {
                    Optional<GameProfile> player;
                    if ((player = cache.getByUuid(user.getID())).isPresent()) {
                        builder.suggest(player.get().getName());
                    } else {
                        builder.suggest(user.getID().toString());
                    }
                }
                return builder.buildFuture();
            };
        }

        static SuggestionProvider<ServerCommandSource> openFactions() {
            return suggest(user ->
                Faction.all()
                    .stream()
                    .filter(Faction::isOpen)
                    .map(Faction::getName)
                    .toArray(String[]::new)
            );
        }

        static SuggestionProvider<ServerCommandSource> openInvitedFactions() {
            return suggest(user ->
                Faction.all()
                    .stream()
                    .filter(f -> f.isOpen() || f.isInvited(user.getID()))
                    .map(Faction::getName)
                    .toArray(String[]::new)
            );
        }

        static <T extends Enum<T>> SuggestionProvider<ServerCommandSource> enumSuggestion (Class<T> clazz) {
            return suggest(user ->
                    Arrays.stream(clazz.getEnumConstants())
                        .map(Enum::toString)
                        .toArray(String[]::new)
            );
        }

        static SuggestionProvider<ServerCommandSource> suggest(Suggests sug) {
            return (context, builder) -> {
                ServerPlayerEntity entity = context.getSource().getPlayer();
                User user = User.get(entity.getUuid());
                for (String suggestion : sug.run(user)) {
                    builder.suggest(suggestion);
                }
                return builder.buildFuture();
            };
        }
    }

    static User getUser(ServerPlayerEntity player) {
        User user = User.get(player.getUuid());
        if (user.getSpoof() == null) {
            return user;
        }
        return user.getSpoof();
    }
}