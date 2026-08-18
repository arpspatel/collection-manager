package io.neebu.apps.core;

import io.neebu.apps.conn.DatabaseApp;
import io.neebu.apps.conn.TmdbApiClient;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.MediaFile;
import io.neebu.apps.core.models.TmdbTitle;
import io.neebu.apps.utils.CollectionUtils;
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

        try (DatabaseApp databaseApp = new DatabaseApp()) {
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

            int deleteCount = 0;
            for (Map.Entry<String, String> entry : fileActionMap.entrySet()) {
                if ("DELETE".equals(entry.getValue())) {
                    LOGGER.info("Deleting DB entry: {}", entry.getKey());
                    databaseApp.delete(entry.getKey());
                    deleteCount++;
                }
            }

            long skipCount = fileActionMap.values().stream().filter("SKIP"::equals).count();

            List<String> addPaths = fileActionMap.entrySet().stream()
                    .filter(e -> "ADD".equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            LOGGER.info("Found {} new movie file(s) to add", addPaths.size());

            // Build + TMDb-enrich each new movie concurrently (independent per file); DB writes
            // stay single-threaded on this connection.
            List<MediaFile> readyToInsert = CollectionUtils.parallelMap(addPaths, Constants.SCAN_THREAD_POOL_SIZE,
                    filePath -> buildAndEnrichMovie(filePath, appProperties));
            skipCount += addPaths.size() - readyToInsert.size();

            databaseApp.insertBatch(readyToInsert);
            int addCount = readyToInsert.size();

            for (MediaFile mediaFile : readyToInsert) {
                mediaFile.applyNamingConvention();
                if (mediaFile.isRenameRequired() && appProperties.isRenameMovies()) {
                    try {
                        LOGGER.info("Renaming movie: {} → {}", mediaFile.getAbsolutePath(), mediaFile.getNormalizedTitle());
                        Files.move(mediaFile.getAbsolutePath(), mediaFile.getNormalizedTitle());
                    } catch (Exception e) {
                        LOGGER.error("Failed to rename file {} to {}: {}", mediaFile.getAbsolutePath(), mediaFile.getNormalizedTitle(), e.getMessage());
                    }
                }
            }

            LOGGER.info("Movie scan complete. Added={}, Deleted={}, Skipped={}", addCount, deleteCount, skipCount);
        } catch (Exception e) {
            LOGGER.error("Failed to complete movie scan: {}", e.getMessage(), e);
        }
    }

    /**
     * Builds a MediaFile and resolves its TMDb title. Returns null (logged) on any failure so
     * one bad file doesn't abort the run.
     */
    private static MediaFile buildAndEnrichMovie(String filePath, AppProperties appProperties) {
        try {
            LOGGER.info("Adding new movie file: {}", filePath);
            MediaFile mediaFile = new MediaFile(Paths.get(filePath), Constants.CollectionType.MOVIE);

            TmdbTitle tmdbTitle = fetchMovieTitle(appProperties, mediaFile);
            if (tmdbTitle == null) {
                LOGGER.warn("Skipping movie due to missing TMDb info: {}", filePath);
                return null;
            }

            enrichMediaWithTitle(mediaFile, tmdbTitle);
            return mediaFile;
        } catch (Exception e) {
            LOGGER.error("Error handling movie {}: {}", filePath, e.getMessage(), e);
            return null;
        }
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
