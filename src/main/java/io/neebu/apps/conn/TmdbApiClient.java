package io.neebu.apps.conn;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.neebu.apps.pojos.CollectionType;
import io.neebu.apps.pojos.TmdbEpisode;
import io.neebu.apps.pojos.TmdbTitle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class TmdbApiClient {

    private static final String TV_EPISODE_URL = "https://api.themoviedb.org/3/tv/";

    private static final Gson gson = new Gson();

    public static TmdbTitle getTmdbId(String tmdbApiKey, String tmdbApiUri, CollectionType collectionType, String titleName, String titleYear) throws Exception {
        String encodedTitle = URLEncoder.encode(titleName, StandardCharsets.UTF_8);
        StringBuilder urlBuilder = new StringBuilder(tmdbApiUri)
                .append("/search/").append(collectionType.toString().toLowerCase())
                .append("?api_key=").append(tmdbApiKey)
                .append("&language=en")
                .append("&query=").append(encodedTitle);

        if (titleYear != null) {
            urlBuilder.append("&year=").append(titleYear);
        }

        System.out.println("Uri: " + urlBuilder.toString());

        JsonObject json = getJsonFromUrl(urlBuilder.toString());
        JsonArray results = json.getAsJsonArray("results");

        if (results != null && results.size() > 0) {
            JsonObject firstResult = results.get(0).getAsJsonObject();

            TmdbTitle tmdbTitle = new TmdbTitle();
            tmdbTitle.setTmdbId(firstResult.get("id").getAsInt());

            if(collectionType.equals(CollectionType.MOVIE)) {
                tmdbTitle.setTmdbName(firstResult.get("title").getAsString());
                if (firstResult.has("release_date") && !firstResult.get("release_date").isJsonNull()) {
                    tmdbTitle.setReleaseDate(firstResult.get("release_date").getAsString());
                }
            }
            if(collectionType.equals(CollectionType.TV)) {
                tmdbTitle.setTmdbName(firstResult.get("name").getAsString());
                if (firstResult.has("first_air_date") && !firstResult.get("first_air_date").isJsonNull()) {
                    tmdbTitle.setReleaseDate(firstResult.get("first_air_date").getAsString());
                }
            }
            tmdbTitle.setTmdbDescription(firstResult.get("overview").getAsString());
            return tmdbTitle;
        }
        return new TmdbTitle();
    }


    // Movie methods (same as before)
    public static TmdbTitle getMovieByTmdbId(String tmdbApiKey, String tmdbApiUri, Integer tmdbId) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(tmdbApiUri)
                .append("/movie/").append(tmdbId.toString())
                .append("?api_key=").append(tmdbApiKey)
                .append("&language=en");

        JsonObject json = getJsonFromUrl(urlBuilder.toString());

        TmdbTitle tmdbTitle = new TmdbTitle();
        tmdbTitle.setTmdbId(json.get("id").getAsInt());
        tmdbTitle.setTmdbName(json.get("title").getAsString());
        tmdbTitle.setTmdbDescription(json.get("overview").getAsString());

        if (json.has("release_date") && !json.get("release_date").isJsonNull()) {
            tmdbTitle.setReleaseDate(json.get("release_date").getAsString());
        }
        return tmdbTitle;
    }


    public static TmdbEpisode getTvShowEpisodeInfo(String tmdbApiKey, String tmdbApiUri, Integer tvShowId, String seasonNumber, String episodeNumber) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(tmdbApiUri)
                .append("/tv/").append(tvShowId.toString())
                .append("/season/").append(seasonNumber)
                .append("/episode/").append(episodeNumber)
                .append("?api_key=").append(tmdbApiKey)
                .append("&language=en");

        System.out.println(urlBuilder);
//        String urlStr = TV_EPISODE_URL + tvShowId + "/season/" + seasonNumber + "/episode/" + episodeNumber + "?api_key=" + tmdbApiKey + "&language=en";
        JsonObject json = getJsonFromUrl(urlBuilder.toString());

        TmdbEpisode episode = new TmdbEpisode();
        episode.setEpisodeNumber(json.get("episode_number").getAsString());
        episode.setSeasonNumber(json.get("season_number").getAsString());
        episode.setName(json.get("name").getAsString());
        episode.setOverview(json.has("overview") && !json.get("overview").isJsonNull() ? json.get("overview").getAsString() : null);

        return episode;
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
            throw new RuntimeException("Failed : HTTP error code : " + status);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return gson.fromJson(reader, JsonObject.class);
        } finally {
            conn.disconnect();
        }
    }
}