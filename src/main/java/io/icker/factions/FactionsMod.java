package io.icker.factions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.icker.factions.api.events.ClaimEvents;
import io.icker.factions.api.persistents.Claim;
import io.icker.factions.command.*;
import io.icker.factions.config.Config;
import io.icker.factions.core.*;
import io.icker.factions.util.Command;
import io.icker.factions.util.DynmapWrapper;
import io.icker.factions.util.PlaceholdersWrapper;
import io.icker.factions.util.WorldUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class FactionsMod implements ModInitializer {
    public static Logger LOGGER = LogManager.getLogger("Factions");
    public static final String MODID = "factions";

    public static Config CONFIG = Config.load();
    public static DynmapWrapper dynmap;

    public static final int MAX_CHUNKS_FILL = 100;

    @Override
    public void onInitialize() {
        LOGGER.info("Initialized Factions Mod for Minecraft v1.19");

        dynmap = FabricLoader.getInstance().isModLoaded("dynmap") ? new DynmapWrapper() : null;
        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            PlaceholdersWrapper.init();
        }

        ChatManager.register();
        FactionsManager.register();
        InteractionManager.register();
        ServerManager.register();
        SoundManager.register();
        WorldManager.register();
        WorldUtils.register();

        CommandRegistrationCallback.EVENT.register(FactionsMod::registerCommands);
        ClaimEvents.ADD.register(FactionsMod::callFill);
    }

    private record Point(int x, int z) {
        public Point atDelta(int dx, int dz) {
            return new Point(x + dx, z + dz);
        }

        public Point[] getNeighbors() {
            return new Point[] {
                    atDelta(1, 0),
                    atDelta(-1, 0),
                    atDelta(0, 1),
                    atDelta(0, -1)
            };
        }

        public Point[] getDiagonals() {
            return new Point[] {
                    atDelta(1, 1),
                    atDelta(1, -1),
                    atDelta(-1, 1),
                    atDelta(-1, -1)
            };
        }

        public int directionTo(Point other) {
            if (other.x == x) {
                return other.z > z ? 2 : 3;
            } else if(other.z == z){
                return other.x > x ? 0 : 1;
            }
            return -1; // SHOULDNT HAPPEN !!!
        }

        public int distanceTo(Point other) {
            return Math.abs(other.x - x) + Math.abs(other.z - z);
        }

        /**
         * Checks if the point is closer to the target than the previous point.
         * @param target The target point.
         * @param from The previous point
         * @return True if the point is closer to the target than the previous point.
         */
        public boolean goesCloserTo(Point target, Point from) {
            return distanceTo(target) < from.distanceTo(target);
        }
    }

    /**
     * <pre>
     * Index 0 is (x + 1, z    )
     * Index 1 is (x - 1, z    )
     * Index 2 is (x,     z + 1)
     * Index 3 is (x,     z - 1)
     * @param point The point to get connections for.
     * @param factionID The factionID of the claim we are checking. This is required to check the claim against other claims.
     * @param level The level of the claim we are checking. This is required to get a claim.
     * @return A boolean array, where true indicates a connection in the corresponding direction.
     */
    private static boolean[] getConnections(Point point, UUID factionID, String level) {
        boolean[] connections = {false, false, false, false};
        Point[] neighbors = point.getNeighbors();
        for (int i = 0; i < 4; i++) {
            Point neighbor = neighbors[i];
            Claim neighborClaim = Claim.get(neighbor.x, neighbor.z, level);
            connections[i] = neighborClaim != null && neighborClaim.factionID == factionID;
        }
        return connections;
    }

    /**
     * Returns the number of connections in the given boolean array.
     * @param connections Should be from {@link #getConnections(Point, UUID, String)}
     * @return The number of true values (i.e. connections).
     */
    public static int numberOfConnections(boolean[] connections) {
        int count = 0;
        for (boolean connection : connections) {
            if (connection) count++;
        }
        return count;
    }

    public static void callFill(Claim newClaim) {
        try {
            fill(newClaim);
        } catch (StackOverflowError | OutOfMemoryError e) {
            LOGGER.error("AYO I RAN OUTTA RAM MAN (while filling chunks)", e);
        } catch (ArrayIndexOutOfBoundsException e) {
            for(StackTraceElement element : e.getStackTrace()) {
                LOGGER.error(element.toString());
            }
        }
    }

    private static void fill(Claim newClaim) {
        Point start = new Point(newClaim.x, newClaim.z);
        UUID factionID = newClaim.factionID;
        String level = newClaim.level;

        boolean[] directions = getConnections(start, factionID, level);
        // LOGGER.info("Here's the connections: \n" + Arrays.toString(directions));
        // Count the # of connections. If the # of connections is less than 2, return.
        // Having only one connection means that the line is incomplete (no polygon, obviously), and no connections is the same thing.
        int connections = numberOfConnections(directions);
        if(connections < 2) return;

        // Since newClaim has 2 (or more) adjacent neighbors, there is a chance that it completes a bounding polygon on the map.
        // However, we need to determine whether there is actually a polygon that is newly closed by newClaim.
        // There could be multiple polygons that are newly enclosed, so we may need to fill multiple.
        BooleanAndFillsArrays fillInfo = getDirectionsToFill(start, factionID, level, directions, connections);
        LOGGER.info("Here's the fill directions: \n" + Arrays.deepToString(fillInfo.fills()));

        // Now that we know where we need to fill from (that took me 5 hours to code), we can start filling.

        Set<Point> allPoints = new HashSet<>();
        for(int i = 0; i < fillInfo.bools.length; i++) {
            if(fillInfo.bools[i]) { // we should fill
                Set<Point> points = new HashSet<>();
                Point init;
                if(fillInfo.fills[i].wasParallel) { // it was parallel, so we actually need to get the direct neighbor on fill
                    LOGGER.info("parallel, using parallel dir");
                    init = start.getNeighbors()[fillInfo.fills[i].parallelDir];
                } else {
                    LOGGER.info("not parallel, using diagonal dir");
                    init = start.getDiagonals()[fillInfo.fills[i].fill];
                }
                if(Claim.get(init.x, init.z, level) != null) {
                    LOGGER.info("claim at " + init + " already exists, either a bug or a 2x2 edgecase.");
                    continue;
                }
                points.add(init);
                LOGGER.info("filling starting from " + init);
                floodFill(points, level);
                allPoints.addAll(points);
            }
        }

        // Now that we have all the points, we can add the claims.
        for(Point point : allPoints) {
            if(Claim.get(point.x, point.z, level) == null) {
                LOGGER.info("adding point at " + point);
                Claim claim = new Claim(point.x, point.z, level, factionID);
                Claim.addWithoutRerunning(claim);
            }

        }
    }

    /**
     * we love copying code
     * @param points A set of points. You should keep this to add the actual claims in because i ain't doin that for ya
     * @param level The level of the claim we are checking. This is required to get a claim.
     */
    private static void floodFill(Set<Point> points, String level) {
        Set<Point> expansion = new HashSet<>();
        for(Point point : points) {
            Point[] neighbors = point.getNeighbors();
            for(Point neighbor : neighbors) {
                if(points.contains(neighbor)) continue;
                Claim claim = Claim.get(neighbor.x, neighbor.z, level);
                if(claim != null) continue;
                // LOGGER.info("adding point " + neighbor + " to expansion");
                expansion.add(neighbor);
            }
        }
        points.addAll(expansion);

        if(expansion.size() == 0 || points.size() > MAX_CHUNKS_FILL) {
            return;
        }
        floodFill(points, level);
    }

    private record BooleanAndFillsArrays(boolean[] bools, FillAndWasParallel[] fills) {}

    /**
     * <pre>
     * Checks the directions of a claim to see if it is connected to another claim.
     * To do so, we must follow each connection, and attempt to find a path back to newClaim.
     * While doing so, we must attempt to find the shortest path back to newClaim.
     * At each point with another connection, first follow the connection that will keep our distance to newClaim the smallest.
     * If we find a path back to newClaim, we have found a newly closed polygon.
     * Otherwise, we need to keep following the line.
     * Since we are in Minecraft, it would be impossible to keep going to an infinite distance.
     * Therefore, we can just keep iterating until the line ends. This is otherwise a very poor heuristic.
     * This should be repeated for each connection in case there are multiple polygons that are newly closed by newClaim.
     * It isn't possible that we miss another polygon with this method, because if there were another polygon, it would have been already closed by another claim or will be handled by traversing another connection.
     *
     * This method returns a boolean array indicating which corners should be flood filled from. The return array is organized like so:
     * Index 0 is (x + 1, z + 1)
     * Index 1 is (x + 1, z - 1)
     * Index 2 is (x - 1, z + 1)
     * Index 3 is (x - 1, z - 1)
     * @param start The start point which we will attempt to find all shortest paths to.
     * @param factionID The factionID of the claim we are checking. This is required to check the claim against other claims.
     * @param level The level of the claim we are checking. This is required to get a claim.
     * @param directions The known directions of neighbors. There should be at least 2 true values in this array, THIS WILL NOT BE VERIFIED. This array should be from {@link #getConnections(Point, UUID, String)}.
     * @param connections The number of connections. This should be from {@link #numberOfConnections(boolean[])}, called with the same array as directions. This saves a bit of re-running code.
     * @return A double boolean array, the first indicating which corners we should flood fill from, and the second boolean array indicating which directions were parallel and therefore should have their new point along the axis and not diagonal.
     */
    private static BooleanAndFillsArrays getDirectionsToFill(final Point start, final UUID factionID, final String level, final boolean[] directions, final int connections) {
        // If the claim has only two connections, we must check if the connections are perpendicular. If so, we only need to fill the diagonal between them.
        // The process is the same regardless of the number of connections: we must traverse back to the start.
        // However, we only need to check until (connections - 1) connections have been fully traversed.
        // This is because the last connection must be returned to by a previous one and as such a polygon has already been completed.

        // However, if we do complete a polygon, we must take note of which direction we went to complete it, since if the completing directions were parallel,
        // We need to fill one of the sides. We must therefore note which direction the completion line was, from the original point.
        // It would be impossible for the completion line to cut back across the initial parallel line, since that would mean that the polygon was already closed.
        int fullyTraversed = 0;
        boolean[] fillDirections = {false, false, false, false};
        FillAndWasParallel[] parallels = new FillAndWasParallel[4];

        Set<Point> traversalSet = new HashSet<>();
        for(int i = 0; i < directions.length; i++) {
            if(fullyTraversed >= connections - 1) {
                LOGGER.info("reached the max # of necessary traversals");
                break;
            }
            if(!directions[i]) {
                // LOGGER.info("skipping since there isn't a connection");
                continue;
            }
            // LOGGER.info("traversing");
            // We must traverse in the direction of the connection.
            ArrayList<Point> path = new ArrayList<>();
            FillAndWasParallel fill = traverse(start, factionID, level, start, i, i, -1, true, traversalSet, path);
            if(fill.fill != -1) {
                int index = fill.fill;
                fillDirections[index] = true;
                parallels[index] = fill;
                LOGGER.info("Found a polygon, path:\n" + path);
                Point init;
                int runs = 0;
                if(parallels[index].wasParallel) { // it was parallel, so we actually need to get the direct neighbor on fill
                    init = start.getNeighbors()[parallels[index].parallelDir];
                    while(!modifiedPointInPolygon(path, init) || Claim.get(init.x, init.z, level) != null) {
                        LOGGER.info("parallel, point " + init + " not in polygon, rotating");
                        parallels[index] = new FillAndWasParallel(parallels[index].fill, parallels[index].wasParallel, (parallels[index].parallelDir + 1) % 4);
                        init = start.getNeighbors()[parallels[index].parallelDir];
                        if(runs++ > 3) {
                            LOGGER.info("runs exceeded 3, breaking (shouldn't happen?)");
                            break;
                        }
                    }
                } else {
                    init = start.getDiagonals()[parallels[index].fill];
                    while(!modifiedPointInPolygon(path, init) || Claim.get(init.x, init.z, level) != null) {
                        LOGGER.info("not parallel, point " + init + " not in polygon, rotating");
                        parallels[index] = new FillAndWasParallel((parallels[index].fill + 1) % 4, parallels[index].wasParallel, parallels[index].fill);
                        init = start.getDiagonals()[parallels[index].fill];
                        if(runs++ > 3) {
                            LOGGER.info("runs exceeded 3, breaking (shouldn't happen?)");
                            break;
                        }
                    }
                }
            }
            fullyTraversed++;
        }
        return new BooleanAndFillsArrays(fillDirections, parallels);
    }

    private static boolean modifiedPointInPolygon(ArrayList<Point> polygonPath, Point point) {
        // Use just the x value to check if the point is in the polygon.
        // If we pass through adjacent points, count those adjacent points only as one.
        int passes = 0;
        boolean lastCheckWasCross = false;
        for(Point p : polygonPath) {
            if(p.x == point.x) {
                if(lastCheckWasCross || p.z > point.z) continue;
                passes++;
                lastCheckWasCross = true;
            } else {
                lastCheckWasCross = false;
            }
        }
        return passes % 2 == 1;
    }

    private static int oppositeDirection(int direction) {
        if(direction < 2) {
            return (direction + 1) % 2;
        }
        return (direction + 1) % 2 + 2;
    }

    private record FillAndWasParallel(int fill, boolean wasParallel, int parallelDir) {
        public FillAndWasParallel(int fill, boolean wasParallel) {
            this(fill, wasParallel, -1);
        }
    }

    /**
     * Recursively traverses in the indicated direction. Returns an integer, indicating which index of the fill array should be filled, or -1 on fail.
     * If we meet a branch where either option has the same distance to the start, we only need to pick one; if it succeeds we do not need to follow the second option since it will be traversed later if it is a new polygon.
     * Checking the distance to the start is easy; if the direction is the same as the parameter, don't do that one. Also don't go the opposite direction of the parameter, that'd just be stupid.
     * @param start The start point. This should be constant across the recursion.
     * @param factionID The factionID of the claim we are checking. This is required to check the claim against other claims. This should be constant across the recursion.
     * @param level The level of the claim we are checking. This is required to get a claim. This should be constant across the recursion.
     * @param from The point to start traversing from. This is the new branch point.
     * @param direction The direction we should traverse from the branch point.
     * @param initialDirection The direction the very first call gave us. This is used to determine which corner to fill. Should be direction on the first call.
     * @param lastMainBranchDirection The last direction we chose on the main branch. Used to handle parallel lines. Should be -1 on the first call.
     * @param firstBranch Whether this is the first branch. This is used when handling parallel lines. If true, lastMainBranchDirection will be updated to the last branch taken on the main traversal. If false, lastMainBranchDirection will never be updated within this iteration.
     * @param traversed A set of points we have already traversed. This is used to avoid infinite recursion in certain scenarios.
     * @param path The path taken by the traversal. If you want this later, store it before calling.
     * @return The index of the fill array to fill, or -1 on fail.
     */
    private static FillAndWasParallel traverse(final Point start, final UUID factionID, final String level, final Point from, int direction, int initialDirection, int lastMainBranchDirection, final boolean firstBranch, Set<Point> traversed, List<Point> path) {
        Point previous = start;
        Point current = from.getNeighbors()[direction];
        path.add(current);
        if(!firstBranch) {
            traversed.add(from); // maybe maybe maybe
        }
        LOGGER.info("added " + from + " (from point) to traversed");
        boolean[] connections = getConnections(current, factionID, level);
        int numConnections = numberOfConnections(connections);
        while(numConnections == 2 && connections[direction] && !current.equals(start)) { // While the # of connections is 2, we keep straight-lining our traversal.
            previous = current;
            current = current.getNeighbors()[direction];
            connections = getConnections(current, factionID, level);
            numConnections = numberOfConnections(connections);
            LOGGER.info("Checking: " + current + " against " + start);
            traversed.add(current);
            path.add(current);
            LOGGER.info("added " + current + " (current) to traversed");
        }
        // If the number of connections is 1, we have reached the end of the line.
        if(numConnections == 1) return new FillAndWasParallel(-1, false);

        LOGGER.info("currently at: " + current + ", start is: " + start + ", iterating from: " + from);

        if(current.equals(start)) { // We've returned to the start (somehow)
            // If we were potentially parallel, we need to fill the direction we last picked on the main branch.
            int returnDirectionFromLastMainBranch = lastMainBranchDirection % 2 == 0 ? 0 : 3;
            boolean[] connectionsFromStart = getConnections(start, factionID, level);
            int numConnectionsFromStart = numberOfConnections(connectionsFromStart);
            if(numConnectionsFromStart == 2) { // If the only possible original scenario was a parallel line meetup, we use lastMainBranchDirection to determine fill direction.
                if((connectionsFromStart[0] && connectionsFromStart[1]) || (connectionsFromStart[2] && connectionsFromStart[3])) { // If we have 2 connections to the start, we must have ended on the parallel line. We must check if the direction of secondToLast -> start is the same as the direction of our initial traverse.
                    LOGGER.info("only scenario was parallel");
                    return new FillAndWasParallel(returnDirectionFromLastMainBranch, true, lastMainBranchDirection);
                }
            }
            // If we have more than 2 connections to the start, we may still have ended on the parallel line. We must check if the direction of secondToLast -> start is the same as the direction of our initial traverse.
            if(previous.directionTo(start) == initialDirection) { // previous is our second to last point we were at
                LOGGER.info("it was actually parallel upon checking, the previous was " + previous);
                return new FillAndWasParallel(returnDirectionFromLastMainBranch, true, lastMainBranchDirection);
            }
            LOGGER.info("wasn't parallel");
            // We must have met up from a perpendicular connection, so we use secondToLast to determine fill direction.
            int startToSecondToLast = start.directionTo(previous);
            if(initialDirection == 0) {
                if(startToSecondToLast == 2) return new FillAndWasParallel(0, false);
                return new FillAndWasParallel(1, false);
            }
            if(initialDirection == 1) {
                if(startToSecondToLast == 2) return new FillAndWasParallel(2, false);
                return new FillAndWasParallel(3, false);
            }
            if(initialDirection == 2) {
                if(startToSecondToLast == 0) return new FillAndWasParallel(0, false);
                return new FillAndWasParallel(2, false);
            }
            if(initialDirection == 3) {
                if(startToSecondToLast == 0) return new FillAndWasParallel(1, false);
                return new FillAndWasParallel(3, false);
            }

            LOGGER.info("FUCK SHIT BALLS");
            return new FillAndWasParallel(-1, false); // SHOULDNT HAPPEN !!!
        }

        // We now have either 3 or 4 connections. One of those is the way we came (bad). Potentially, we have one that might take us back to the start.
        // The hope is that the direction going back towards our start contains the shortest path, so we want to prioritize that.
        // Since we only have 4 possible adjacent points, one of them will hopefully contain a decrease in distance to the start, without also being the direction we came from.
        // Since we can avoid worrying about missing other polygons, just traverse all directions which are closer to the start until we get a result.
        int[] badDirections = {-1, -1, -1}; // turns out we CAN have 3 shitty directions to go im sad now
        int numBadDirections = 0;
        Point[] neighbors = current.getNeighbors();
        for(int i = 0; i < connections.length; i++) {
            boolean bl = connections[i];
            if(bl && oppositeDirection(i) != direction && !traversed.contains(neighbors[i])) { // If we have a connection and it isn't the direction we came from, AND we haven't already traversed that point, we consider traversing it
                // if the distance is an increase, we don't want to traverse it unless it's our only remaining option.
                if(!neighbors[i].goesCloserTo(start, current) || (firstBranch && i == direction)) { // if we're not getting closer, do this later. Also, prefer branching to going straight.
                    badDirections[numBadDirections++] = i;
                    continue;
                }
                if(firstBranch) { // if we're the first branch, we're cool and get to do what we want with lastMainBranchDirection
                    lastMainBranchDirection = i; // We're the first branch so we get to do this with no repercussions.
                }
                ArrayList<Point> subPath = new ArrayList<>();
                FillAndWasParallel fill = traverse(start, factionID, level, current, i, initialDirection, lastMainBranchDirection, false, traversed, subPath); // this one isn't the first branch, so we don't want to update lastMainBranchDirection.
                if(fill.fill != -1) {
                    path.addAll(subPath);
                    return fill; // if we get a good result from our traversal we're done.
                }
            } else {
                LOGGER.info("Direction " + i + " skipped (skipping point: " + neighbors[i] + ")");
            }
        }
        // ok, so we didn't get a good result from traversing the directions that go closer to the start. We now traverse the other directions.
        // we don't need to repeat checks here since these have already been checked
        for(int dir : badDirections) {
            if(dir == -1) continue;
            if(firstBranch) { // if we're the first branch, we're cool and get to do what we want with lastMainBranchDirection
                lastMainBranchDirection = dir; // We're the first branch so we get to do this with no repercussions.
            }
            LOGGER.info("trying out bad directions");
            ArrayList<Point> subPath = new ArrayList<>();
            FillAndWasParallel fill = traverse(start, factionID, level, current, dir, initialDirection, lastMainBranchDirection, false, traversed, subPath); // this one isn't the first branch, so we don't want to update lastMainBranchDirection.
            if(fill.fill != -1) {
                path.addAll(subPath);
                return fill; // if we get a good result from our traversal we're done.
            }
        }

        LOGGER.info("didn't find anything, cry");
        return new FillAndWasParallel(-1, false); // we found nothing, cry about it we probably just traversed 50 billion chunks for no reason
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        LiteralCommandNode<ServerCommandSource> factions = CommandManager
                .literal("factions")
                .build();

        LiteralCommandNode<ServerCommandSource> alias = CommandManager
                .literal("f")
                .build();
        LiteralCommandNode<ServerCommandSource> alias2 = CommandManager
                .literal("faction")
                .build();

        dispatcher.getRoot().addChild(factions);
        dispatcher.getRoot().addChild(alias);
        dispatcher.getRoot().addChild(alias2);

        Command[] commands = new Command[] {
                new AdminCommand(),
                new SettingsCommand(),
                new ClaimCommand(),
                new CreateCommand(),
                new DeclareCommand(),
                new DisbandCommand(),
                new HomeCommand(),
                new InfoCommand(),
                new InviteCommand(),
                new JoinCommand(),
                new KickCommand(),
                new LeaveCommand(),
                new ListCommand(),
                new MapCommand(),
                new MemberCommand(),
                new ModifyCommand(),
                new RankCommand(),
                // new SafeCommand(),
                new PermissionCommand()
        };

        for (Command command : commands) {
            factions.addChild(command.getNode());
            alias.addChild(command.getNode());
            alias2.addChild(command.getNode());
        }
    }
}
