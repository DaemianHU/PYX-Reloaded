package net.socialgamer.cah.cardcast;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.SoftReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;


public class CardcastService {
    private static final Logger LOG = Logger.getLogger(CardcastService.class);
    private static final String HOSTNAME = "api.cardcastgame.com";

    /**
     * Base URL to the Cardcast API.
     */
    private static final String BASE_URL = "https://" + HOSTNAME + "/v1/decks/";
    /**
     * URL to the Cardcast API for information about a card set. The only format replacement is the
     * string deck ID.
     */
    private static final String CARD_SET_INFO_URL_FORMAT_STRING = BASE_URL + "%s";
    /**
     * URL to the Cardcast API for cards in a card set. The only format replacement is the string
     * deck ID.
     */
    private static final String CARD_SET_CARDS_URL_FORMAT_STRING = CARD_SET_INFO_URL_FORMAT_STRING + "/cards";

    private static final int GET_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(3);

    /**
     * How long to cache nonexistent card sets, or after an error occurs while querying for the card
     * set. We need to do this to prevent DoS attacks.
     */
    private static final long INVALID_SET_CACHE_LIFETIME = TimeUnit.SECONDS.toMillis(30);

    /**
     * How long to cache valid card sets.
     */
    private static final long VALID_SET_CACHE_LIFETIME = TimeUnit.MINUTES.toMillis(15);
    private static final Pattern validIdPattern = Pattern.compile("[A-Z0-9]{5}");
    private static final Map<String, SoftReference<CardcastCacheEntry>> cache = Collections.synchronizedMap(new HashMap<String, SoftReference<CardcastCacheEntry>>());

    @Nullable
    private CardcastCacheEntry checkCache(String setId) {
        SoftReference<CardcastCacheEntry> soft = cache.get(setId);
        if (soft == null) return null;
        else return soft.get();
    }

    public CardcastDeck loadSet(final String setId) {
        if (!validIdPattern.matcher(setId).matches()) return null;

        CardcastCacheEntry cached = checkCache(setId);
        if (cached != null && cached.expires > System.currentTimeMillis()) {
            LOG.info(String.format("Using cache: %s=%s", setId, cached.deck));
            return cached.deck;
        } else if (cached != null) {
            LOG.info(String.format("Cache stale: %s", setId));
        } else {
            LOG.info(String.format("Cache miss: %s", setId));
        }

        try {
            String infoContent = getUrlContent(String.format(CARD_SET_INFO_URL_FORMAT_STRING, setId));
            if (infoContent == null) {
                // failed to load
                cacheMissingSet(setId);
                return null;
            }

            JsonObject info = new Gson().toJsonTree(infoContent).getAsJsonObject();

            String cardContent = getUrlContent(String.format(CARD_SET_CARDS_URL_FORMAT_STRING, setId));
            if (cardContent == null) {
                // failed to load
                cacheMissingSet(setId);
                return null;
            }

            JsonObject cards = new Gson().toJsonTree(cardContent).getAsJsonObject();

            String name = info.get("name").getAsString();
            String description = info.get("description").getAsString();
            if (name == null || description == null || name.isEmpty()) {
                // We require a name. Blank description is acceptable, but cannot be null.
                cacheMissingSet(setId);
                return null;
            }
            CardcastDeck deck = new CardcastDeck(StringEscapeUtils.escapeXml11(name), setId, StringEscapeUtils.escapeXml11(description));

            // load up the cards
            JsonArray blacks = cards.getAsJsonArray("calls");
            if (blacks != null) {
                for (JsonElement black : blacks) {
                    JsonArray texts = black.getAsJsonObject().getAsJsonArray("text");
                    if (texts != null) {
                        List<String> strs = new ArrayList<>(texts.size());
                        for (JsonElement text : texts) strs.add(text.getAsString());
                        String text = StringUtils.join(strs, "____");
                        int pick = strs.size() - 1;
                        int draw = (pick >= 3 ? pick - 1 : 0);
                        deck.getBlackCards().add(new CardcastBlackCard(CardIdUtils.getNewId(), StringEscapeUtils.escapeXml11(text), draw, pick, setId));
                    }
                }
            }

            JsonArray whites = cards.getAsJsonArray("responses");
            if (whites != null) {
                for (JsonElement white : whites) {
                    JsonArray texts = white.getAsJsonObject().getAsJsonArray("text");
                    if (texts != null) {
                        // The white cards should only ever have one element in text, but let's be safe.
                        List<String> strs = new ArrayList<>(texts.size());
                        for (JsonElement text : texts) {
                            String cardCastString = text.getAsString();
                            if (cardCastString.isEmpty()) {
                                // skip blank segments
                                continue;
                            }

                            StringBuilder pyxString = new StringBuilder();

                            // Cardcast's recommended format is to not capitalize the first letter
                            pyxString.append(cardCastString.substring(0, 1).toUpperCase());
                            pyxString.append(cardCastString.substring(1));

                            // Cardcast's recommended format is to not include a period
                            if (Character.isLetterOrDigit(cardCastString.charAt(cardCastString.length() - 1)))
                                pyxString.append('.');

                            // Cardcast's white cards are now formatted consistently with pyx cards
                            strs.add(pyxString.toString());
                        }

                        String text = StringUtils.join(strs, "");
                        // don't add blank cards, they don't do anything
                        if (!text.isEmpty())
                            deck.getWhiteCards().add(new CardcastWhiteCard(CardIdUtils.getNewId(), StringEscapeUtils.escapeXml11(text), setId));
                    }
                }
            }

            cacheSet(setId, deck);
            return deck;
        } catch (Exception ex) {
            LOG.error(String.format("Unable to load deck %s from Cardcast", setId), ex);
            cacheMissingSet(setId);
            return null;
        }
    }

    private void cachePut(String setId, CardcastDeck deck, long timeout) {
        LOG.info(String.format("Caching %s=%s for %d ms", setId, deck, timeout));
        cache.put(setId, new SoftReference<>(new CardcastCacheEntry(timeout, deck)));
    }

    private void cacheSet(String setId, CardcastDeck deck) {
        cachePut(setId, deck, VALID_SET_CACHE_LIFETIME);
    }

    private void cacheMissingSet(String setId) {
        cachePut(setId, null, INVALID_SET_CACHE_LIFETIME);
    }

    private String getUrlContent(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setDoInput(true);
        conn.setDoOutput(false);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setReadTimeout(GET_TIMEOUT);
        conn.setConnectTimeout(GET_TIMEOUT);

        int code = conn.getResponseCode();
        if (HttpURLConnection.HTTP_OK != code) {
            LOG.error(String.format("Got HTTP response code %d from Cardcast for %s", code, urlStr));
            return null;
        }

        String contentType = conn.getContentType();
        if (!Objects.equals(contentType, "application/json")) {
            LOG.error(String.format("Got content-type %s from Cardcast for %s", contentType, urlStr));
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder builder = new StringBuilder(4096);

            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append('\n');
            }

            return builder.toString();
        }
    }

    private class CardcastCacheEntry {
        final long expires;
        final CardcastDeck deck;

        CardcastCacheEntry(long cacheLifetime, CardcastDeck deck) {
            this.expires = System.currentTimeMillis() + cacheLifetime;
            this.deck = deck;
        }
    }
}
