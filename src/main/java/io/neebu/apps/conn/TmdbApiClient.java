package io.neebu.apps.conn;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.neebu.apps.core.models.TmdbEpisode;
import io.neebu.apps.core.models.TmdbTitle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * TMDb API client for fetching movie and TV show metadata.
 * <p>
 * Provides static methods to interact with TMDb REST API endpoints for movies and TV shows.
 * Handles HTTP requests, JSON parsing, and error logging.
 * <p>
 * Usage example:
 * TmdbTitle title = TmdbApiClient.getTmdbMovieId(apiKey, apiUri, "Inception", 2010);
 */
public class TmdbApiClient {

    private static final Logger logger = LoggerFactory.getLogger(TmdbApiClient.class);
    private static final Gson gson = new Gson();
    private static final int TIMEOUT_MS = 5000;

    /**
     * Fetches movie details by TMDb ID.
     * @param tmdbApiKey TMDb API key
     * @param tmdbApiUri TMDb API base URI
     * @param tmdbId     TMDb movie ID
     * @return TmdbTitle object with movie details, or null if not found
     * @throws Exception on network or parsing error
     */
    public static TmdbTitle getMovieByTmdbId(String tmdbApiKey, String tmdbApiUri, Integer tmdbId) throws Exception {
        String url = String.format("%s/movie/%d?api_key=%s&language=en", tmdbApiUri, tmdbId, tmdbApiKey);
        logger.info("Requesting TMDb movie by ID: {}", tmdbId);
        logger.debug("GET URL: {}", url);
        JsonObject json = getJsonFromUrl(url);
        logger.info("Received TMDb movie response for ID: {}", tmdbId);
        return parseMovieJson(json, tmdbId);
    }

    /**
     * Fetches episode info for a TV show.
     * @param tmdbApiKey TMDb API key
     * @param tmdbApiUri TMDb API base URI
     * @param tvShowId   TMDb TV show ID
     * @param seasonNumber Season number
     * @param episodeNumber Episode number
     * @return TmdbEpisode object with episode details, or null if not found
     * @throws Exception on network or parsing error
     */
    public static TmdbEpisode getTvShowEpisodeInfo(String tmdbApiKey, String tmdbApiUri, Integer tvShowId, Integer seasonNumber, Integer episodeNumber) throws Exception {
        String url = String.format("%s/tv/%d/season/%s/episode/%s?api_key=%s&language=en",
                tmdbApiUri, tvShowId, seasonNumber, episodeNumber, tmdbApiKey);
        logger.info("Requesting TMDb episode info: TV ID {}, Season {}, Episode {}", tvShowId, seasonNumber, episodeNumber);
        logger.debug("GET URL: {}", url);
        JsonObject json = getJsonFromUrl(url);
        logger.info("Received TMDb episode response: TV ID {}, Season {}, Episode {}", tvShowId, seasonNumber, episodeNumber);
        return parseEpisodeJson(json, seasonNumber, episodeNumber);
    }

    /**
     * Searches for a movie by name and year.
     * @param tmdbApiKey TMDb API key
     * @param tmdbApiUri TMDb API base URI
     * @param titleName  Movie name
     * @param titleYear  Release year (optional)
     * @return TmdbTitle object with movie details, or null if not found
     * @throws Exception on network or parsing error
     */
    public static TmdbTitle getTmdbMovieId(String tmdbApiKey, String tmdbApiUri, String titleName, Integer titleYear) throws Exception {
        String encodedTitle = URLEncoder.encode(titleName, StandardCharsets.UTF_8);
        String url = tmdbApiUri + "/search/movie?api_key=" + tmdbApiKey + "&language=en&query=" + encodedTitle + (titleYear != null ? "&year=" + titleYear : "");
        logger.info("Searching TMDb movie: '{}' ({})", titleName, titleYear);
        logger.debug("GET URL: {}", url);
        JsonObject json = getJsonFromUrl(url);
        JsonArray results = json.getAsJsonArray("results");
        logger.info("Received TMDb movie search response for: '{}' ({})", titleName, titleYear);
        if (results != null && !results.isEmpty()) {
            logger.info("TMDb movie found: '{}' ({})", titleName, titleYear);
            return parseMovieJson(results.get(0).getAsJsonObject(), null);
        }
        logger.warn("No TMDb movie found for: '{}' ({})", titleName, titleYear);
        return null;
    }

    /**
     * Fetches TV show details by TMDb ID.
     * @param tmdbApiKey TMDb API key
     * @param tmdbApiUri TMDb API base URI
     * @param tmdbId     TMDb TV show ID
     * @return TmdbTitle object with TV show details, or null if not found
     * @throws Exception on network or parsing error
     */
    public static TmdbTitle getTmdbTvId(String tmdbApiKey, String tmdbApiUri, Integer tmdbId) throws Exception {
        String url = String.format("%s/tv/%d?api_key=%s&language=en", tmdbApiUri, tmdbId, tmdbApiKey);
        logger.info("Requesting TMDb TV show by ID: {}", tmdbId);
        logger.debug("GET URL: {}", url);
        JsonObject json = getJsonFromUrl(url);
        logger.info("Received TMDb TV show response for ID: {}", tmdbId);
        return parseTvJson(json, tmdbId);
    }

    /**
     * Searches for a TV show by name, season, and episode.
     * @param tmdbApiKey TMDb API key
     * @param tmdbApiUri TMDb API base URI
     * @param titleName  TV show name
     * @param seasonNumber Season number
     * @param episodeNumber Episode number
     * @return TmdbTitle object with TV show details, or null if not found
     * @throws Exception on network or parsing error
     */
    public static TmdbTitle getTmdbTvId(String tmdbApiKey, String tmdbApiUri, String titleName, Integer seasonNumber, Integer episodeNumber) throws Exception {
        String encodedTitle = URLEncoder.encode(titleName, StandardCharsets.UTF_8);
        String url = tmdbApiUri + "/search/tv?api_key=" + tmdbApiKey + "&language=en&query=" + encodedTitle;
        logger.info("Searching TMDb TV show: '{}' (Season {}, Episode {})", titleName, seasonNumber, episodeNumber);
        logger.debug("GET URL: {}", url);
        JsonObject json = getJsonFromUrl(url);
        JsonArray results = json.getAsJsonArray("results");
        logger.info("Received TMDb TV show search response for: '{}' (Season {}, Episode {})", titleName, seasonNumber, episodeNumber);
        if (results != null && !results.isEmpty() && seasonNumber != null && episodeNumber != null) {
            for (JsonElement element : results) {
                JsonObject resultObj = element.getAsJsonObject();
                int tvId = resultObj.get("id").getAsInt();
                String episodeUrl = String.format(
                        "%s/tv/%d/season/%s/episode/%s?api_key=%s&language=en",
                        tmdbApiUri, tvId, seasonNumber, episodeNumber, tmdbApiKey
                );
                logger.debug("Checking episode existence for TV ID {}: {}", tvId, episodeUrl);
                if (isHttp200(episodeUrl)) {
                    TmdbTitle tmdbTitle = parseTvJson(resultObj, tvId);
                    logger.info("TMDb TV show found: '{}' (ID: {})", tmdbTitle.getTmdbName(), tmdbTitle.getTmdbId());
                    return tmdbTitle;
                } else {
                    logger.warn("Episode not found (HTTP != 200) for TV ID {}", tvId);
                }
            }
        }
        logger.warn("No matching TMDb TV show found for: '{}' (Season {}, Episode {})", titleName, seasonNumber, episodeNumber);
        return null;
    }

    /**
     * Checks if a URL returns HTTP 200 without downloading the full content.
     * @param urlStr URL to check
     * @return true if HTTP 200, false otherwise
     */
    private static boolean isHttp200(String urlStr) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int statusCode = connection.getResponseCode();
            logger.debug("HTTP HEAD {} returned status {}", urlStr, statusCode);
            return statusCode == HttpURLConnection.HTTP_OK;
        } catch (IOException e) {
            logger.error("HTTP check failed for {}: {}", urlStr, e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Utility method to do HTTP GET and parse JSON response.
     * @param urlStr URL to fetch
     * @return JsonObject parsed from response
     * @throws Exception on network or parsing error
     */
    private static JsonObject getJsonFromUrl(String urlStr) throws Exception {
        URI uri = URI.create(urlStr);
        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        int status = conn.getResponseCode();
        logger.debug("HTTP GET {} returned status {}", urlStr, status);
        if (status != 200) {
            logger.error("Failed to fetch data from {}. HTTP Status: {}", urlStr, status);
            throw new RuntimeException("HTTP error code: " + status);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            logger.debug("Parsing JSON response from {}", urlStr);
            return gson.fromJson(reader, JsonObject.class);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Safely get a string field from a JSON object.
     * @param json JsonObject
     * @param key  Field key
     * @return String value or null
     */
    private static String getSafeString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    /**
     * Parse movie JSON to TmdbTitle.
     * @param json   JsonObject for movie
     * @param tmdbId Optional TMDb ID
     * @return TmdbTitle object
     */
    private static TmdbTitle parseMovieJson(JsonObject json, Integer tmdbId) {
        if (json == null) return null;
        TmdbTitle tmdbTitle = new TmdbTitle();
        tmdbTitle.setTmdbId(tmdbId != null ? tmdbId : json.get("id").getAsInt());
        tmdbTitle.setTmdbName(getSafeString(json, "title"));
        tmdbTitle.setTmdbDescription(getSafeString(json, "overview"));
        tmdbTitle.setReleaseDate(getSafeString(json, "release_date"));
        logger.debug("Parsed TMDb movie: {} (ID: {})", tmdbTitle.getTmdbName(), tmdbTitle.getTmdbId());
        return tmdbTitle;
    }

    /**
     * Parse TV show JSON to TmdbTitle.
     * @param json   JsonObject for TV show
     * @param tmdbId Optional TMDb ID
     * @return TmdbTitle object
     */
    private static TmdbTitle parseTvJson(JsonObject json, Integer tmdbId) {
        if (json == null) return null;
        TmdbTitle tmdbTitle = new TmdbTitle();
        tmdbTitle.setTmdbId(tmdbId != null ? tmdbId : json.get("id").getAsInt());
        tmdbTitle.setTmdbName(getSafeString(json, "name"));
        tmdbTitle.setTmdbDescription(getSafeString(json, "overview"));
        tmdbTitle.setReleaseDate(getSafeString(json, "first_air_date"));
        logger.debug("Parsed TMDb TV show: {} (ID: {})", tmdbTitle.getTmdbName(), tmdbTitle.getTmdbId());
        return tmdbTitle;
    }

    /**
     * Parse episode JSON to TmdbEpisode.
     * @param json         JsonObject for episode
     * @param seasonNumber Season number
     * @param episodeNumber Episode number
     * @return TmdbEpisode object
     */
    private static TmdbEpisode parseEpisodeJson(JsonObject json, Integer seasonNumber, Integer episodeNumber) {
        if (json == null) return null;
        TmdbEpisode episode = new TmdbEpisode();
        episode.setEpisodeNumber(getSafeString(json, "episode_number"));
        episode.setSeasonNumber(getSafeString(json, "season_number"));
        episode.setName(getSafeString(json, "name"));
        episode.setOverview(getSafeString(json, "overview"));
        logger.debug("Parsed TMDb episode: S{}E{} - {}", seasonNumber, episodeNumber, episode.getName());
        return episode;
    }
}
