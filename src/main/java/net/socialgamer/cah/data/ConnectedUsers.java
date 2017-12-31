package net.socialgamer.cah.data;

import com.google.gson.JsonObject;
import com.maxmind.geoip2.model.CityResponse;
import net.socialgamer.cah.Constants.DisconnectReason;
import net.socialgamer.cah.Constants.ErrorCode;
import net.socialgamer.cah.Constants.LongPollEvent;
import net.socialgamer.cah.Constants.LongPollResponse;
import net.socialgamer.cah.Utils;
import net.socialgamer.cah.data.QueuedMessage.MessageType;
import net.socialgamer.cah.metrics.GeoIP;
import net.socialgamer.cah.metrics.Metrics;
import org.apache.log4j.Logger;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;


public class ConnectedUsers {
    /**
     * Duration of a ping timeout, in nanoseconds.
     */
    public static final long PING_TIMEOUT = TimeUnit.SECONDS.toNanos(90);
    /**
     * Duration of an idle timeout, in nanoseconds.
     */
    public static final long IDLE_TIMEOUT = TimeUnit.MINUTES.toNanos(60);
    private static final Logger logger = Logger.getLogger(ConnectedUsers.class);
    private final boolean broadcastConnectsAndDisconnects;
    private final int maxUsers;
    private final GeoIP geoIp;
    private final Metrics metrics;
    /**
     * Key (username) must be stored in lower-case to facilitate case-insensitivity in nicks.
     */
    private final Map<String, User> users = new HashMap<>();

    public ConnectedUsers(boolean broadcastConnectsAndDisconnects, int maxUsers, GeoIP geoIp, Metrics metrics) {
        this.broadcastConnectsAndDisconnects = broadcastConnectsAndDisconnects;
        this.maxUsers = maxUsers;
        this.geoIp = geoIp;
        this.metrics = metrics;
    }

    /**
     * @param userName User name to check.
     * @return True if {@code userName} is a connected user.
     */
    public boolean hasUser(String userName) {
        return users.containsKey(userName.toLowerCase());
    }

    /**
     * Checks to see if the specified {@code user} is allowed to connect, and if so, add the user,
     * as an atomic operation.
     *
     * @param user User to add. {@code getNickname()} is used to determine the nickname.
     * @return {@code null} if the user was added, or an {@link ErrorCode} explaining why the user was
     * rejected.
     */
    @Nullable
    public ErrorCode checkAndAdd(User user) {
        synchronized (users) {
            if (this.hasUser(user.getNickname())) {
                logger.info(String.format("Rejecting existing username %s from %s", user.toString(), user.getHostname()));
                return ErrorCode.NICK_IN_USE;
            } else if (users.size() >= maxUsers && !user.isAdmin()) {
                logger.warn(String.format("Rejecting user %s due to too many users (%d >= %d)", user.toString(), users.size(), maxUsers));
                return ErrorCode.TOO_MANY_USERS;
            } else {
                logger.info(String.format("New user %s from %s (admin=%b)", user.toString(), user.getHostname(), user.isAdmin()));
                users.put(user.getNickname().toLowerCase(), user);
                if (broadcastConnectsAndDisconnects) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty(LongPollResponse.EVENT.toString(), LongPollEvent.NEW_PLAYER.toString());
                    obj.addProperty(LongPollResponse.NICKNAME.toString(), user.getNickname());
                    broadcastToAll(MessageType.PLAYER_EVENT, obj);
                }

                // log them in the metrics
                CityResponse geo = null;
                try {
                    final InetAddress addr = InetAddress.getByName(user.getHostname());
                    geo = geoIp.getInfo(addr);
                } catch (final UnknownHostException e) {
                    logger.warn(String.format("Unable to get address for user %s (hostname: %s)",
                            user.getNickname(), user.getHostname()), e);
                }

                metrics.userConnect(user.getPersistentId(), user.getSessionId(), geo, user.getAgentName(),
                        user.getAgentType(), user.getAgentOs(), user.getAgentLanguage());

                return null;
            }
        }
    }

    /**
     * Remove a user from the user list, and mark them as invalid so the next time they make a request
     * they can be informed.
     *
     * @param user   User to remove.
     * @param reason Reason the user is being removed.
     */
    public void removeUser(User user, DisconnectReason reason) {
        synchronized (users) {
            if (users.containsKey(user.getNickname())) {
                logger.info(String.format("Removing user %s because %s", user.toString(), reason));
                user.noLongerValid();
                users.remove(user.getNickname().toLowerCase());
                notifyRemoveUser(user, reason);
            }
        }
    }

    /**
     * Get the User for the specified nickname, or null if no such user exists.
     *
     * @param nickname
     * @return User, or null.
     */
    @Nullable
    public User getUser(String nickname) {
        return users.get(nickname.toLowerCase());
    }

    /**
     * Broadcast to all remaining users that a user has left. Also logs for metrics.
     *
     * @param user   User that has left.
     * @param reason Reason why the user has left.
     */
    private void notifyRemoveUser(User user, DisconnectReason reason) {
        // Games are informed about the user leaving when the user object is marked invalid.
        if (broadcastConnectsAndDisconnects) {
            JsonObject obj = new JsonObject();
            obj.addProperty(LongPollResponse.EVENT.toString(), LongPollEvent.PLAYER_LEAVE.toString());
            obj.addProperty(LongPollResponse.NICKNAME.toString(), user.getNickname());
            obj.addProperty(LongPollResponse.REASON.toString(), reason.toString());
            broadcastToAll(MessageType.PLAYER_EVENT, obj);
        }

        metrics.userDisconnect(user.getSessionId());
    }

    /**
     * Check for any users that have not communicated with the server within the ping timeout delay,
     * and remove users which have not so communicated. Also remove clients which are still connected,
     * but have not actually done anything for a long time.
     */
    public void checkForPingAndIdleTimeouts() {
        final Map<User, DisconnectReason> removedUsers = new HashMap<>();
        synchronized (users) {
            Iterator<User> iterator = users.values().iterator();
            while (iterator.hasNext()) {
                User user = iterator.next();

                DisconnectReason reason = null;
                if (System.nanoTime() - user.getLastHeardFrom() > PING_TIMEOUT) {
                    reason = DisconnectReason.PING_TIMEOUT;
                } else if (!user.isAdmin() && System.nanoTime() - user.getLastUserAction() > IDLE_TIMEOUT) {
                    reason = DisconnectReason.IDLE_TIMEOUT;
                }

                if (reason != null) {
                    removedUsers.put(user, reason);
                    iterator.remove();
                }
            }
        }

        // Do this later to not keep users locked
        for (Entry<User, DisconnectReason> entry : removedUsers.entrySet()) {
            try {
                entry.getKey().noLongerValid();
                notifyRemoveUser(entry.getKey(), entry.getValue());
                logger.info(String.format("Automatically kicking user %s due to %s", entry.getKey(), entry.getValue()));
            } catch (Exception ex) {
                logger.error("Unable to remove pinged-out user", ex);
            }
        }
    }

    /**
     * Broadcast a message to all connected players.
     *
     * @param type       Type of message to broadcast. This determines the order the messages are returned by
     *                   priority.
     * @param masterData Message data to broadcast.
     */
    public void broadcastToAll(MessageType type, JsonObject masterData) {
        broadcastToList(users.values(), type, masterData);
    }

    /**
     * Broadcast a message to a specified subset of connected players.
     *
     * @param broadcastTo List of users to broadcast the message to.
     * @param type        Type of message to broadcast. This determines the order the messages are returned by
     *                    priority.
     * @param masterData  Message data to broadcast.
     */
    public void broadcastToList(Collection<User> broadcastTo, MessageType type, JsonObject masterData) {
        // TODO I think this synchronized block is pointless.
        synchronized (users) {
            for (User user : broadcastTo) {
                JsonObject obj = Utils.singletonJsonObject(LongPollResponse.TIMESTAMP.toString(), System.currentTimeMillis());
                for (String key : masterData.keySet()) obj.add(key, masterData.get(key));
                user.enqueueMessage(new QueuedMessage(type, obj));
            }
        }
    }

    /**
     * @return A copy of the list of connected users.
     */
    public Collection<User> getUsers() {
        synchronized (users) {
            return new ArrayList<>(users.values());
        }
    }
}
