package io.neebu.apps.core;

import io.neebu.apps.conn.DatabaseApp;
import io.neebu.apps.conn.TmdbApiClient;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.MediaFile;
import io.neebu.apps.core.models.TmdbTitle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MovieScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MovieScanner.class);

    public static void run(AppProperties appProperties, List<String> filesList) {
        LOGGER.info("Running movie organiser...");

        DatabaseApp databaseApp = new DatabaseApp();
        databaseApp.connect(appProperties.getDatabaseUrl(), appProperties.getDatabaseUser(), appProperties.getDatabasePass());

        List<String> dbCollection = databaseApp.getCollection(Constants.SELECT_MOVIES_SQL);
        LOGGER.info("Retrieved {} movie records from database", dbCollection.size());

        Set<String> dbSet = new HashSet<>(dbCollection);
        Set<String> fileSet = new HashSet<>(filesList);

        Map<String, String> fileActionMap = Stream.of(filesList, dbCollection)
                .flatMap(Collection::stream)
                .distinct()
                .collect(Collectors.toMap(file -> file,
                        file -> getCollectionAction(dbSet.contains(file), fileSet.contains(file))));

        int addCount = 0, deleteCount = 0, skipCount = 0;

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
                        LOGGER.info("Adding new movie file: {}", filePath);
                        MediaFile mediaFile = new MediaFile(Paths.get(filePath), Constants.CollectionType.MOVIE);

                        TmdbTitle tmdbTitle = fetchMovieTitle(appProperties, mediaFile);
                        if (tmdbTitle == null) {
                            LOGGER.warn("Skipping movie due to missing TMDb info: {}", filePath);
                            skipCount++;
                            continue;
                        }

                        enrichMediaWithTitle(mediaFile, tmdbTitle);
                        databaseApp.insert(mediaFile);
                        addCount++;

                        mediaFile.applyNamingConvention();
                        if (mediaFile.isRenameRequired() && appProperties.isRenameMovies()) {
                            LOGGER.info("Renaming movie: {} → {}", mediaFile.getAbsolutePath(), mediaFile.getNormalizedTitle());
                            Files.move(mediaFile.getAbsolutePath(), mediaFile.getNormalizedTitle());
                        }
                    }
                    default -> {
                        LOGGER.debug("No action for file (SKIP): {}", filePath);
                        skipCount++;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error handling movie {}: {}", filePath, e.getMessage(), e);
                skipCount++;
            }
        }

        databaseApp.close();
        LOGGER.info("Movie scan complete. Added={}, Deleted={}, Skipped={}", addCount, deleteCount, skipCount);
    }

    private static TmdbTitle fetchMovieTitle(AppProperties props, MediaFile mediaFile) {
        try {
            if (mediaFile.isHasTmdbId()) {
                return TmdbApiClient.getMovieByTmdbId(
                        props.getTmdbApiKey(),
                        props.getTmdbApiUri(),
                        Integer.parseInt(mediaFile.getFileTmdbId())
                );
            }
            return TmdbApiClient.getTmdbMovieId(
                    props.getTmdbApiKey(),
                    props.getTmdbApiUri(),
                    mediaFile.getName(),
                    mediaFile.getReleaseYear()
            );
        } catch (Exception e) {
            LOGGER.error("Failed to fetch TMDb movie data for '{}': {}", mediaFile.getName(), e.getMessage());
            return null;
        }
    }

    private static void enrichMediaWithTitle(MediaFile mediaFile, TmdbTitle tmdbTitle) {
        mediaFile.setTmdbId(tmdbTitle.getTmdbId());
        mediaFile.setTmdbName(tmdbTitle.getTmdbName());
        mediaFile.setReleaseDate(tmdbTitle.getReleaseDate());
        mediaFile.setTmdbDescription(tmdbTitle.getTmdbDescription());
        LOGGER.info("Associated TMDb title (ID: {}, Name: {})", tmdbTitle.getTmdbId(), tmdbTitle.getTmdbName());
    }

    private static String getCollectionAction(boolean existInDatabase, boolean existInPath) {
        if (existInDatabase && !existInPath) return "DELETE";
        if (!existInDatabase && existInPath) return "ADD";
        return "SKIP";
    }
}
