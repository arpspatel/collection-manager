package io.neebu.apps.core;

import io.neebu.apps.conn.TmdbApiClient;
import io.neebu.apps.core.models.TmdbEpisode;
import io.neebu.apps.core.models.TmdbTitle;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TvShowScanner {

    private static final Pattern EPISODE_PATTERN = Pattern.compile(
            "^(.+?)[. _-]+(?:[Ss](\\d{1,4})[Ee](\\d{1,4})|(\\d{1,4})x(\\d{1,4})).*\\.[^.]+$"
    );

    private final String tmdbApiKey;
    private final String tmdbApiUri;

    public TvShowScanner(String tmdbApiKey, String tmdbApiUri) {
        this.tmdbApiKey = tmdbApiKey;
        this.tmdbApiUri = tmdbApiUri;
    }

    static class EpisodeInfo {
        String filePath;
        String showName;
        int season;
        int episode;
        int tmdbId;
        String seasonPad;
        String episodePad;
        String episodeName;
        String overview;
        String releaseDate;

        EpisodeInfo(String filePath, String showName, int season, int episode) {
            this.filePath = filePath;
            this.showName = showName;
            this.season = season;
            this.episode = episode;
        }
    }

    static class ShowMetadata {
        int tmdbId;
        int seasonPadLength;
        int episodePadLength;
    }

    public void scanDirectory(String baseDir) throws Exception {
        Map<String, EpisodeInfo> fileMap = new LinkedHashMap<>();
        Map<String, Map<Integer, Integer>> showSeasonEpisodeMap = new HashMap<>();

        // Pass 1: Scan files and extract metadata
        Files.walk(Paths.get(baseDir))
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().matches(".*\\.(mkv|mp4|avi)$"))
                .forEach(path -> {
                    Matcher m = EPISODE_PATTERN.matcher(path.getFileName().toString());
                    if (m.matches()) {
                        String show = cleanShowName(m.group(1));
                        int season = parseIntSafe(m.group(2), m.group(4));
                        int episode = parseIntSafe(m.group(3), m.group(5));

                        EpisodeInfo info = new EpisodeInfo(path.toString(), show, season, episode);
                        fileMap.put(path.toString(), info);

                        showSeasonEpisodeMap
                                .computeIfAbsent(show, k -> new HashMap<>())
                                .merge(season, episode, Math::max);
                    }
                });

        // Pass 2: Determine padding & fetch TMDb IDs
        Map<String, ShowMetadata> tmdbMap = new HashMap<>();
        for (String show : showSeasonEpisodeMap.keySet()) {
            Map<Integer, Integer> seasonMap = showSeasonEpisodeMap.get(show);
            int maxSeason = seasonMap.keySet().stream().max(Integer::compare).orElse(1);
            int maxEpisode = seasonMap.getOrDefault(maxSeason, 1);

            int seasonPad = String.valueOf(maxSeason).length();
            int episodePad = String.valueOf(maxEpisode).length();

            TmdbTitle title = getTmdbTvId(tmdbApiKey, tmdbApiUri, show,
                    String.valueOf(maxSeason), String.valueOf(maxEpisode));

            ShowMetadata meta = new ShowMetadata();
            meta.tmdbId = title.getTmdbId();
            meta.seasonPadLength = seasonPad;
            meta.episodePadLength = episodePad;
            tmdbMap.put(show, meta);
        }

        // Pass 3: Update file map with TMDb ID and padding
        fileMap.values().forEach(info -> {
            ShowMetadata meta = tmdbMap.get(info.showName);
            if (meta != null) {
                info.tmdbId = meta.tmdbId;
                info.seasonPad = String.format("%0" + meta.seasonPadLength + "d", info.season);
                info.episodePad = String.format("%0" + meta.episodePadLength + "d", info.episode);
            }
        });

        // Pass 4: Fetch episode details
        for (EpisodeInfo info : fileMap.values()) {
            if (info.tmdbId > 0) {
                try {
                    TmdbEpisode ep = getTvShowEpisodeInfo(tmdbApiKey, tmdbApiUri, info.tmdbId,
                            String.valueOf(info.season), String.valueOf(info.episode));
                    info.episodeName = ep.getName();
                    info.overview = ep.getOverview();
                    info.releaseDate = ep.getReleaseDate();
                } catch (Exception e) {
                    System.err.println("Failed to fetch episode info for " + info.filePath + ": " + e.getMessage());
                }
            }
        }
    }

    private static String cleanShowName(String raw) {
        return raw.replace('.', ' ').replace('_', ' ').trim();
    }

    private static int parseIntSafe(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    // These two methods should be the ones we already implemented earlier
    private static TmdbTitle getTmdbTvId(String apiKey, String apiUri, String showName,
                                         String seasonNumber, String episodeNumber) throws Exception {
        // Call your optimized getTmdbTvId() here
        return TmdbApiClient.getTmdbTvId(apiKey, apiUri, showName, Integer.valueOf(seasonNumber), Integer.valueOf(episodeNumber));
    }

    private static TmdbEpisode getTvShowEpisodeInfo(String apiKey, String apiUri, int tvShowId,
                                                    String seasonNumber, String episodeNumber) throws Exception {
        // Call your existing getTvShowEpisodeInfo() here
        return TmdbApiClient.getTvShowEpisodeInfo(apiKey, apiUri, tvShowId, Integer.valueOf(seasonNumber), Integer.valueOf(episodeNumber));
    }
}

