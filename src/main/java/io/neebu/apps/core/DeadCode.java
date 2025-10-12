package io.neebu.apps.core;

import io.neebu.apps.App;
import io.neebu.apps.conn.DatabaseApp;
import io.neebu.apps.conn.TmdbApiClient;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.MediaFile;
import io.neebu.apps.core.models.TmdbEpisode;
import io.neebu.apps.core.models.TmdbTitle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class DeadCode {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeadCode.class);


    public static void runCollectionOrganiser(Constants.CollectionType collectionType, AppProperties appProperties, List<String> filesList) {
        LOGGER.debug("Establishing database connection...");
        DatabaseApp databaseApp = new DatabaseApp();
        databaseApp.connect(appProperties.getDatabaseUrl(), appProperties.getDatabaseUser(), appProperties.getDatabasePass());

        List<String> dbCollection = switch (collectionType) {
            case TV -> databaseApp.getCollection(Constants.SELECT_TV_SQL);
            case MOVIE -> databaseApp.getCollection(Constants.SELECT_MOVIES_SQL);
        };

        LOGGER.info("Retrieved {} records from database for {}", dbCollection.size(), collectionType);

        Set<String> dbSet = new HashSet<>(dbCollection);
        Set<String> fileSet = new HashSet<>(filesList);

        // Map file to action (ADD, DELETE, SKIP)
        Map<String, String> fileActionMap = Stream.of(filesList, dbCollection)
                .flatMap(Collection::stream)
                .distinct()
                .collect(HashMap::new, (map, file) -> map.put(file, getCollectionAction(dbSet.contains(file), fileSet.contains(file))), HashMap::putAll);

        Map<String, TmdbTitle> titleCache = new HashMap<>();

        // Action counters
        int addCount = 0;
        int deleteCount = 0;
        int skipCount = 0;

        for (Map.Entry<String, String> entry : fileActionMap.entrySet()) {
            String filePath = entry.getKey();
            String action = entry.getValue();

            try {
                switch (action) {
                    case "DELETE" -> {
                        LOGGER.info("Deleting DB entry: {}", filePath);
                        databaseApp.delete(filePath);
                        deleteCount++;
                    }

                    case "ADD" -> {
                        LOGGER.info("Adding new media file: {}", filePath);
                        MediaFile mediaFile = new MediaFile(Paths.get(filePath), collectionType);

                        TmdbTitle tmdbTitle = switch (collectionType) {
                            case MOVIE -> fetchMovieTitle(appProperties, mediaFile);
                            case TV -> fetchTvTitle(appProperties, mediaFile, titleCache);
                        };

                        if (tmdbTitle == null) {
                            LOGGER.warn("Skipping file due to missing TMDb info: {}", filePath);
                            skipCount++;
                            continue;
                        }

                        enrichMediaWithTitle(mediaFile, tmdbTitle);

                        // Fetch episode info for TV
                        if (collectionType == Constants.CollectionType.TV) {
                            try {
                                TmdbEpisode episode = TmdbApiClient.getTvShowEpisodeInfo(
                                        appProperties.getTmdbApiKey(),
                                        appProperties.getTmdbApiUri(),
                                        mediaFile.getTmdbId(),
                                        Integer.valueOf(mediaFile.getSeasonNumber()),
                                        Integer.valueOf(mediaFile.getEpisodeNumber())
                                );
                                mediaFile.setEpisodeName(episode.getName());
                                mediaFile.setEpisodeOverview(episode.getOverview());
                            } catch (Exception e) {
                                LOGGER.warn("Could not fetch episode info for {}: {}", filePath, e.getMessage());
                            }
                        }

                        databaseApp.insert(mediaFile);
                        LOGGER.debug("Inserted into DB: {}", filePath);
                        addCount++;

                        mediaFile.applyNamingConvention();

                        boolean renameEnabled = switch(collectionType){
                            case MOVIE -> appProperties.isRenameMovies();
                            case TV -> appProperties.isRenameTv();
                        };
                        if (mediaFile.isRenameRequired() && renameEnabled) {
                            LOGGER.info("Renaming file: {} → {}", mediaFile.getAbsolutePath(), mediaFile.getNormalizedTitle());
                            Files.move(mediaFile.getAbsolutePath(), mediaFile.getNormalizedTitle());
                        }
                    }

                    default -> {
                        LOGGER.debug("No action for file (SKIP): {}", filePath);
                        skipCount++;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error handling file {}: {}", filePath, e.getMessage(), e);
                skipCount++;
            }
        }

        LOGGER.debug("Closing DB connection...");
        databaseApp.close();

        LOGGER.info("Summary for {} collection:", collectionType);
        LOGGER.info("  ➕ Added:   {}", addCount);
        LOGGER.info("  ❌ Deleted: {}", deleteCount);
        LOGGER.info("  ➖ Skipped: {}", skipCount);
    }

    private static TmdbTitle fetchMovieTitle(AppProperties props, MediaFile mediaFile) {
        try {
            if (mediaFile.isHasTmdbId()) {
                return TmdbApiClient.getMovieByTmdbId(props.getTmdbApiKey(), props.getTmdbApiUri(), Integer.parseInt(mediaFile.getFileTmdbId()));
            }
            return TmdbApiClient.getTmdbMovieId(props.getTmdbApiKey(), props.getTmdbApiUri(), mediaFile.getName(), mediaFile.getReleaseYear());
        } catch (Exception e) {
            LOGGER.error("Failed to fetch TMDb movie data for '{}': {}", mediaFile.getName(), e.getMessage());
            return null;
        }
    }

    private static TmdbTitle fetchTvTitle(AppProperties props, MediaFile mediaFile, Map<String, TmdbTitle> titleCache) {
        return titleCache.computeIfAbsent(mediaFile.getName(), name -> {
            try {
                return TmdbApiClient.getTmdbTvId(props.getTmdbApiKey(), props.getTmdbApiUri(), name, Integer.valueOf(mediaFile.getSeasonNumber()), Integer.valueOf(mediaFile.getEpisodeNumber()));
            } catch (Exception e) {
                LOGGER.error("Failed to fetch TMDb TV data for '{}': {}", name, e.getMessage());
                return null;
            }
        });
    }

    private static void enrichMediaWithTitle(MediaFile mediaFile, TmdbTitle tmdbTitle) {
        mediaFile.setTmdbId(tmdbTitle.getTmdbId());
        mediaFile.setTmdbName(tmdbTitle.getTmdbName());
        mediaFile.setReleaseDate(tmdbTitle.getReleaseDate());
        mediaFile.setTmdbDescription(tmdbTitle.getTmdbDescription());
        LOGGER.info("Associated TMDb title (ID: {}, Name: {})", tmdbTitle.getTmdbId(), tmdbTitle.getTmdbName());
    }

    public static String getCollectionAction(Boolean existInDatabase, Boolean existInPath) {
        if (existInDatabase && !existInPath) return "DELETE";
        if (!existInDatabase && existInPath) return "ADD";
        return "SKIP";
    }

}
