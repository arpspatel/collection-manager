package io.neebu.apps;

import io.neebu.apps.core.AppProperties;
import io.neebu.apps.core.MovieScanner;
import io.neebu.apps.core.TvScanner;
import io.neebu.apps.utils.CollectionUtils;
import io.neebu.apps.core.entities.Constants.CollectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Main entry point for the Collection Manager application.
 * <p>
 * This application scans configured directories for TV and Movie media files and processes them
 * using the appropriate scanner. Configuration is loaded from application properties.
 */
public class App {

    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    /**
     * Application entry point. Loads configuration and triggers scanning and processing for TV and Movie collections.
     *
     * @param args Command-line arguments (not used).
     */
    public static void main(String[] args) {
        LOGGER.info("Application starting...");
        try {
            AppProperties appProperties = new AppProperties();
            // Process TV shows if enabled in configuration
            if (appProperties.isParseTv()) {
                processCollection(CollectionType.TV, appProperties, appProperties.getTvShowPaths());
            }
            // Process movies if enabled in configuration
            if (appProperties.isParseMovies()) {
                processCollection(CollectionType.MOVIE, appProperties, appProperties.getMoviePaths());
            }
            LOGGER.info("Application finished successfully.");
        } catch (Exception e) {
            LOGGER.error("Application failed with error: {}", e.getMessage(), e);
        }
    }

    /**
     * Scans the provided folder paths for media files and invokes the appropriate scanner for the collection type.
     *
     * @param collectionType The type of collection (TV or MOVIE).
     * @param appProperties  The application properties/configuration.
     * @param folderPaths    List of folder paths to scan for media files.
     * @throws Exception if scanning or processing fails.
     */
    private static void processCollection(CollectionType collectionType, AppProperties appProperties, List<String> folderPaths) throws Exception {
        if (folderPaths == null || folderPaths.isEmpty()) {
            LOGGER.warn("No folder paths configured for {}", collectionType);
            return;
        }

        LOGGER.info("Scanning {} files from configured paths...", collectionType);

        // Collect all media files from the configured folders
        List<String> mediaFiles = folderPaths.stream()
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .flatMap(path -> CollectionUtils.listFilesRecursively(path).stream())
                .map(Path::toString) // already absolute from the utility
                .toList();

        if (mediaFiles.isEmpty()) {
            LOGGER.warn("No media files found to process for {}", collectionType);
            return;
        }

        LOGGER.info("Found {} file(s) for collection type {}", mediaFiles.size(), collectionType);

        // Invoke the appropriate scanner based on collection type
        switch (collectionType) {
            case MOVIE -> MovieScanner.run(appProperties, mediaFiles);
            case TV -> TvScanner.run(appProperties, mediaFiles);
        }
    }
}
