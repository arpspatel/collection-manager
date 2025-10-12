package io.neebu.apps.conn;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.MediaFile;
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

public class TmdbApiClient {

    private static final Logger logger = LoggerFactory.getLogger(TmdbApiClient.class);
    private static final Gson gson = new Gson();

    public static TmdbTitle getTmdbId(String tmdbApiKey, String tmdbApiUri, Constants.CollectionType collectionType, String titleName, Integer titleYear) throws Exception {
        String encodedTitle = URLEncoder.encode(titleName, StandardCharsets.UTF_8);
        StringBuilder urlBuilder = new StringBuilder(tmdbApiUri)
                .append("/search/").append(collectionType.toString().toLowerCase())
                .append("?api_key=").append(tmdbApiKey)
                .append("&language=en")
                .append("&query=").append(encodedTitle);

        if (titleYear != null) {
            urlBuilder.append("&year=").append(titleYear);
        }

        String url = urlBuilder.toString();
        logger.debug("Fetching TMDB ID from URL: {}", url);

        JsonObject json = getJsonFromUrl(url);
        JsonArray results = json.getAsJsonArray("results");

        if (results != null && results.size() > 0) {
            JsonObject firstResult = results.get(0).getAsJsonObject();
            TmdbTitle tmdbTitle = new TmdbTitle();
            tmdbTitle.setTmdbId(firstResult.get("id").getAsInt());

            if (collectionType == Constants.CollectionType.MOVIE) {
                tmdbTitle.setTmdbName(firstResult.get("title").getAsString());
                tmdbTitle.setReleaseDate(getSafeString(firstResult, "release_date"));
            } else if (collectionType == Constants.CollectionType.TV) {
                tmdbTitle.setTmdbName(firstResult.get("name").getAsString());
                tmdbTitle.setReleaseDate(getSafeString(firstResult, "first_air_date"));
            }

            tmdbTitle.setTmdbDescription(getSafeString(firstResult, "overview"));
            logger.info("TMDB ID fetched: {} - {}", tmdbTitle.getTmdbId(), tmdbTitle.getTmdbName());
            return tmdbTitle;
        }

        logger.warn("No TMDB result found for: {} ({})", titleName, titleYear);
        return new TmdbTitle();
    }




    public static TmdbTitle getMovieByTmdbId(String tmdbApiKey, String tmdbApiUri, Integer tmdbId) throws Exception {
        String url = String.format("%s/movie/%d?api_key=%s&language=en", tmdbApiUri, tmdbId, tmdbApiKey);
        logger.debug("Fetching movie by TMDB ID from URL: {}", url);

        JsonObject json = getJsonFromUrl(url);

        TmdbTitle tmdbTitle = new TmdbTitle();
        tmdbTitle.setTmdbId(json.get("id").getAsInt());
        tmdbTitle.setTmdbName(json.get("title").getAsString());
        tmdbTitle.setTmdbDescription(getSafeString(json, "overview"));
        tmdbTitle.setReleaseDate(getSafeString(json, "release_date"));

        logger.info("Fetched movie details for TMDB ID {}: {}", tmdbId, tmdbTitle.getTmdbName());
        return tmdbTitle;
    }

    public static TmdbEpisode getTvShowEpisodeInfo(String tmdbApiKey, String tmdbApiUri, Integer tvShowId, Integer seasonNumber, Integer episodeNumber) throws Exception {
        String url = String.format("%s/tv/%d/season/%s/episode/%s?api_key=%s&language=en",
                tmdbApiUri, tvShowId, seasonNumber, episodeNumber, tmdbApiKey);
        logger.debug("Fetching episode info from URL: {}", url);

        JsonObject json = getJsonFromUrl(url);

        TmdbEpisode episode = new TmdbEpisode();
        episode.setEpisodeNumber(json.get("episode_number").getAsString());
        episode.setSeasonNumber(json.get("season_number").getAsString());
        episode.setName(json.get("name").getAsString());
        episode.setOverview(getSafeString(json, "overview"));

        logger.info("Fetched episode info: S{}E{} - {}", seasonNumber, episodeNumber, episode.getName());
        return episode;
    }

    public static TmdbTitle getTmdbMovieId(
            String tmdbApiKey,
            String tmdbApiUri,
            String titleName,
            Integer titleYear) throws Exception {

        String encodedTitle = URLEncoder.encode(titleName, StandardCharsets.UTF_8);
        StringBuilder urlBuilder = new StringBuilder(tmdbApiUri)
                .append("/search/movie")
                .append("?api_key=").append(tmdbApiKey)
                .append("&language=en")
                .append("&query=").append(encodedTitle);

        if (titleYear != null) {
            urlBuilder.append("&year=").append(titleYear);
        }

        String url = urlBuilder.toString();
        logger.debug("Fetching TMDB Movie from URL: {}", url);

        JsonObject json = getJsonFromUrl(url);
        JsonArray results = json.getAsJsonArray("results");

        if (results != null && results.size() > 0) {
            JsonObject firstResult = results.get(0).getAsJsonObject();
            TmdbTitle tmdbTitle = new TmdbTitle();
            tmdbTitle.setTmdbId(firstResult.get("id").getAsInt());
            tmdbTitle.setTmdbName(getSafeString(firstResult, "title"));
            tmdbTitle.setReleaseDate(getSafeString(firstResult, "release_date"));
            tmdbTitle.setTmdbDescription(getSafeString(firstResult, "overview"));

            logger.info("TMDB Movie fetched: {} - {}", tmdbTitle.getTmdbId(), tmdbTitle.getTmdbName());
            return tmdbTitle;
        }

        logger.warn("No TMDB Movie found for: {} ({})", titleName, titleYear);
        return new TmdbTitle();
    }

    public static TmdbTitle getTmdbTvId(
            String tmdbApiKey,
            String tmdbApiUri,
            String titleName,
            Integer seasonNumber,
            Integer episodeNumber) throws Exception {

        String encodedTitle = URLEncoder.encode(titleName, StandardCharsets.UTF_8);
        StringBuilder urlBuilder = new StringBuilder(tmdbApiUri)
                .append("/search/tv")
                .append("?api_key=").append(tmdbApiKey)
                .append("&language=en")
                .append("&query=").append(encodedTitle);

        String url = urlBuilder.toString();
        logger.debug("Fetching TMDB TV Show(s) from URL: {}", url);

        JsonObject json = getJsonFromUrl(url);
        JsonArray results = json.getAsJsonArray("results");

        if (results != null && results.size() > 0 && seasonNumber != null && episodeNumber != null) {
            for (JsonElement element : results) {
                JsonObject resultObj = element.getAsJsonObject();
                int tvId = resultObj.get("id").getAsInt();

                String episodeUrl = String.format(
                        "%s/tv/%d/season/%s/episode/%s?api_key=%s&language=en",
                        tmdbApiUri, tvId, seasonNumber, episodeNumber, tmdbApiKey
                );

                // Check if the episode exists without downloading the full JSON
                if (isHttp200(episodeUrl)) {
                    // Now fetch full episode details (only once we know it exists)
                    getTvShowEpisodeInfo(tmdbApiKey, tmdbApiUri, tvId, seasonNumber, episodeNumber);

                    TmdbTitle tmdbTitle = new TmdbTitle();
                    tmdbTitle.setTmdbId(tvId);
                    tmdbTitle.setTmdbName(getSafeString(resultObj, "name"));
                    tmdbTitle.setReleaseDate(getSafeString(resultObj, "first_air_date"));
                    tmdbTitle.setTmdbDescription(getSafeString(resultObj, "overview"));

                    logger.info("TMDB TV Show fetched: {} - {}", tmdbTitle.getTmdbId(), tmdbTitle.getTmdbName());
                    return tmdbTitle;
                } else {
                    logger.debug("Episode not found (HTTP != 200) for TV ID {}", tvId);
                }
            }
        }

        logger.warn("No matching TMDB TV Show found for: {}", titleName);
        return new TmdbTitle();
    }

    /**
     * Checks if a URL returns HTTP 200 without downloading the full content.
     */
    private static boolean isHttp200(String urlStr) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int statusCode = connection.getResponseCode();
            connection.disconnect();
            return statusCode == HttpURLConnection.HTTP_OK;
        } catch (IOException e) {
            logger.debug("HTTP check failed for {}: {}", urlStr, e.getMessage());
            return false;
        }
    }


    // Utility method to do HTTP GET and parse JSON response
    private static JsonObject getJsonFromUrl(String urlStr) throws Exception {
        URI uri = URI.create(urlStr);
        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        int status = conn.getResponseCode();
        if (status != 200) {
            logger.error("Failed to fetch data from {}. HTTP Status: {}", urlStr, status);
            throw new RuntimeException("HTTP error code: " + status);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return gson.fromJson(reader, JsonObject.class);
        } finally {
            conn.disconnect();
        }
    }

    // Safely get a string field from a JSON object
    private static String getSafeString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }
}
