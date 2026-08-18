package io.neebu.apps.core;

import io.neebu.apps.conn.DatabaseApp;
import io.neebu.apps.conn.TmdbApiClient;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.MediaFile;
import io.neebu.apps.core.models.TmdbEpisode;
import io.neebu.apps.core.models.TmdbTitle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TV Scanner for managing TV show collections.
 * <p>
 * Synchronizes local TV show files with the database, fetches metadata from TMDb,
 * updates the database, and optionally renames files according to naming conventions.
 * <p>
 * TMDb series-level lookups (show title/overview/release date) are resolved at most
 * once per distinct series per run, no matter how many new episode files belong to it,
 * or how many shows/seasons live under a single configured root. Per-episode lookups
 * still happen once per episode (that data genuinely differs per episode), but - like
 * the series lookup - only ever for files the existing DB-vs-folder diff has flagged
 * ADD, so files already known to the database never trigger API traffic.
 */
public class TvScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TvScanner.class);

    /** Matches season-style folder names: "Season 01", "Season1", "S01", "Series 2", etc. */
    private static final Pattern SEASON_DIR_PATTERN = Pattern.compile("(?i)^(season|se|series|s)[\\s._-]*\\d{1,3}$");

    /**
     * Main entry point for TV scanning and synchronization.
     *
     * @param appProperties Application configuration and credentials.
     * @param filesList     List of absolute file paths to TV show media files.
     */
    public static void run(AppProperties appProperties, List<String> filesList) {
        LOGGER.info("Running TV organiser...");
        try (DatabaseApp databaseApp = new DatabaseApp()) {
            databaseApp.connect(appProperties.getDatabaseUrl(), appProperties.getDatabaseUser(), appProperties.getDatabasePass());
            List<String> dbCollection = databaseApp.getCollection(Constants.SELECT_TV_SQL);
            LOGGER.info("Retrieved {} TV records from database", dbCollection.size());

            Set<String> dbSet = new HashSet<>(dbCollection);
            Set<String> fileSet = new HashSet<>(filesList);

            // Unchanged: classify every known path (on disk and/or in DB) as ADD / DELETE / SKIP.
            Map<String, String> fileActionMap = Stream.concat(filesList.stream(), dbCollection.stream())
                    .distinct()
                    .collect(Collectors.toMap(file -> file,
                            file -> getCollectionAction(dbSet.contains(file), fileSet.contains(file))));

            int deleteCount = 0;
            for (Map.Entry<String, String> entry : fileActionMap.entrySet()) {
                if ("DELETE".equals(entry.getValue())) {
                    LOGGER.debug("Deleting DB entry: {}", entry.getKey());
                    databaseApp.delete(entry.getKey());
                    deleteCount++;
                }
            }

            long skipCount = fileActionMap.values().stream().filter("SKIP"::equals).count();
            int addCount = 0;

            // Only files that actually need to be added get parsed/probed at all -
            // this is the existing DB-vs-folder logic doing its job.
            List<MediaFile> filesToAdd = fileActionMap.entrySet().stream()
                    .filter(e -> "ADD".equals(e.getValue()))
                    .map(e -> buildMediaFile(e.getKey()))
                    .filter(Objects::nonNull)
                    .toList();

            // Group the new files by series so each show is looked up on TMDb exactly
            // once this run, regardless of how many of its episodes are new, and
            // regardless of how many shows live under a single configured root.
            List<String> tvRoots = appProperties.getTvShowPaths();
            Map<String, List<MediaFile>> seriesGroups = filesToAdd.stream()
                    .collect(Collectors.groupingBy(mediaFile -> seriesKey(mediaFile, tvRoots),
                            LinkedHashMap::new, Collectors.toList()));

            LOGGER.info("Found {} new TV file(s) across {} distinct series", filesToAdd.size(), seriesGroups.size());

            Map<String, TmdbTitle> seriesTitleCache = new HashMap<>();

            for (Map.Entry<String, List<MediaFile>> group : seriesGroups.entrySet()) {
                List<MediaFile> episodes = group.getValue();

                // computeIfAbsent guarantees exactly one resolution attempt per series key
                // for the lifetime of this run, however many episodes share that key.
                TmdbTitle tmdbTitle = seriesTitleCache.computeIfAbsent(group.getKey(),
                        k -> resolveSeriesTitle(appProperties, episodes));

                if (tmdbTitle == null) {
                    LOGGER.warn("Skipping {} file(s) - could not resolve series '{}' on TMDb", episodes.size(), group.getKey());
                    skipCount += episodes.size();
                    continue;
                }

                for (MediaFile mediaFile : episodes) {
                    try {
                        LOGGER.info("Adding new TV file: {}", mediaFile.getAbsolutePath());
                        enrichMediaWithTitle(mediaFile, tmdbTitle);
                        fetchAndEnrichEpisode(appProperties, mediaFile);
                        databaseApp.insert(mediaFile);
                        addCount++;
                        renameIfRequired(mediaFile, appProperties);
                    } catch (Exception e) {
                        LOGGER.error("Error handling TV file {}: {}", mediaFile.getAbsolutePath(), e.getMessage(), e);
                        skipCount++;
                    }
                }
            }

            LOGGER.info("TV scan complete. Added={}, Deleted={}, Skipped={}", addCount, deleteCount, skipCount);
        } catch (Exception e) {
            LOGGER.error("Failed to complete TV scan: {}", e.getMessage(), e);
        }
    }

    /**
     * Builds a MediaFile for a path flagged ADD, logging and skipping on failure
     * (e.g. unreadable file) rather than aborting the whole run.
     */
    private static MediaFile buildMediaFile(String filePath) {
        try {
            return new MediaFile(Paths.get(filePath), Constants.CollectionType.TV);
        } catch (Exception e) {
            LOGGER.error("Could not read media file {}: {}", filePath, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Stable grouping key for "same series". Prefers the embedded {tmdb-id} tag when
     * present - authoritative, immune to both filename and folder-naming noise.
     * Otherwise prefers the file's show directory (see {@link #showDirectoryKey}),
     * since a folder name is far more consistent across a show's episodes than
     * scene-release filename text tends to be. Falls back to the normalized parsed
     * title only for files that sit loose in a configured root with no show folder
     * to key on.
     */
    private static String seriesKey(MediaFile mediaFile, List<String> tvRoots) {
        if (mediaFile.isHasTmdbId()) {
            return "id:" + mediaFile.getFileTmdbId();
        }
        String dirKey = showDirectoryKey(mediaFile.getAbsolutePath(), tvRoots);
        if (dirKey != null) {
            return "dir:" + dirKey;
        }
        return "name:" + normalize(mediaFile.getName());
    }

    /**
     * Derives a stable "show directory" key for a file: finds whichever configured TV
     * library root contains it (the most specific one, if roots are nested), then takes
     * the path from that root down to - but not including - a trailing season-style
     * folder such as "Season 01" or "S01", if present.
     * <p>
     * This lets one configured root, however many shows and seasons live under it, group
     * every episode of a show together for TMDb purposes without depending on filename
     * text being parsed identically across episodes. It also means a root dedicated to a
     * single show (season subfolders directly under it, as in a test setup) collapses
     * back to the root itself.
     *
     * @return the normalized show directory path, or null if the file sits directly in a
     *         configured root with no enclosing show folder at all - in that case a
     *         directory alone can't tell two loose files apart, so the caller falls back
     *         to name-based grouping instead.
     */
    private static String showDirectoryKey(Path file, List<String> tvRoots) {
        if (tvRoots == null || tvRoots.isEmpty()) {
            return null;
        }

        Path absoluteFile = file.toAbsolutePath().normalize();
        Path bestRoot = null;

        for (String rootStr : tvRoots) {
            try {
                Path root = Paths.get(rootStr).toAbsolutePath().normalize();
                if (absoluteFile.startsWith(root) && (bestRoot == null || root.getNameCount() > bestRoot.getNameCount())) {
                    bestRoot = root;
                }
            } catch (Exception e) {
                LOGGER.debug("Could not resolve configured TV library path '{}': {}", rootStr, e.getMessage());
            }
        }

        if (bestRoot == null) {
            LOGGER.debug("File {} did not match any configured TV library path", absoluteFile);
            return null;
        }

        Path relative = bestRoot.relativize(absoluteFile);
        int dirSegmentCount = relative.getNameCount() - 1; // exclude the filename itself
        if (dirSegmentCount <= 0) {
            return null; // file sits directly in the root - no show folder to key on
        }

        Path showDir = bestRoot.resolve(relative.subpath(0, dirSegmentCount));
        String lastSegment = showDir.getFileName().toString();
        if (SEASON_DIR_PATTERN.matcher(lastSegment).matches()) {
            Path parent = showDir.getParent();
            if (parent != null && (parent.equals(bestRoot) || parent.startsWith(bestRoot))) {
                // the folder right before the file is a season folder - the show lives one
                // level up, which may be the configured root itself (single-show root)
                showDir = parent;
            }
        }

        return normalize(showDir.toString());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * Resolves the TMDb series title once per group. Tries each episode in the group in
     * turn, falling through on failure, so one malformed/unmatched filename doesn't sink
     * an otherwise resolvable series - but in the common case (consistent naming across
     * a show's episodes) this costs exactly one API call for the whole series.
     */
    private static TmdbTitle resolveSeriesTitle(AppProperties props, List<MediaFile> episodes) {
        for (MediaFile representative : episodes) {
            try {
                TmdbTitle title;
                if (representative.isHasTmdbId()) {
                    LOGGER.info("Fetching TMDb title by ID {} for series '{}' ({} new episode file(s))",
                            representative.getFileTmdbId(), representative.getName(), episodes.size());
                    title = TmdbApiClient.getTmdbTvId(
                            props.getTmdbApiKey(),
                            props.getTmdbApiUri(),
                            Integer.valueOf(representative.getFileTmdbId())
                    );
                } else {
                    LOGGER.info("Searching TMDb for series '{}' via S{}E{} ({} new episode file(s))",
                            representative.getName(), representative.getSeasonNumber(), representative.getEpisodeNumber(), episodes.size());
                    title = TmdbApiClient.getTmdbTvId(
                            props.getTmdbApiKey(),
                            props.getTmdbApiUri(),
                            representative.getName(),
                            Integer.parseInt(representative.getSeasonNumber()),
                            Integer.parseInt(representative.getEpisodeNumber())
                    );
                }
                if (title != null) {
                    LOGGER.debug("Resolved series '{}' -> TMDb ID {}", representative.getName(), title.getTmdbId());
                    return title;
                }
            } catch (Exception e) {
                LOGGER.warn("TMDb lookup failed for '{}' ({}): {}", representative.getName(), representative.getAbsolutePath(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * Enriches a MediaFile object with (cached) TMDb series title information.
     */
    private static void enrichMediaWithTitle(MediaFile mediaFile, TmdbTitle tmdbTitle) {
        mediaFile.setTmdbId(tmdbTitle.getTmdbId());
        mediaFile.setTmdbName(tmdbTitle.getTmdbName());
        mediaFile.setReleaseDate(tmdbTitle.getReleaseDate());
        mediaFile.setTmdbDescription(tmdbTitle.getTmdbDescription());
        LOGGER.info("Associated TMDb TV title (ID: {}, Name: {})", tmdbTitle.getTmdbId(), tmdbTitle.getTmdbName());
    }

    /**
     * Fetches episode-specific info from TMDb and enriches the MediaFile. Not cacheable
     * across files - it is genuinely per-episode - but only ever runs for ADD files.
     */
    private static void fetchAndEnrichEpisode(AppProperties appProperties, MediaFile mediaFile) {
        try {
            TmdbEpisode episode = TmdbApiClient.getTvShowEpisodeInfo(
                    appProperties.getTmdbApiKey(),
                    appProperties.getTmdbApiUri(),
                    mediaFile.getTmdbId(),
                    Integer.parseInt(mediaFile.getSeasonNumber()),
                    Integer.parseInt(mediaFile.getEpisodeNumber())
            );
            mediaFile.setEpisodeName(episode.getName());
            mediaFile.setEpisodeOverview(episode.getOverview());
        } catch (Exception e) {
            LOGGER.warn("Could not fetch episode info for {}: {}", mediaFile.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Renames the media file if required and configured.
     */
    private static void renameIfRequired(MediaFile mediaFile, AppProperties appProperties) {
        mediaFile.applyNamingConvention();
        if (mediaFile.isRenameRequired() && appProperties.isRenameTv()) {
            Path source = mediaFile.getAbsolutePath();
            Path target = mediaFile.getNormalizedTitle();
            try {
                LOGGER.info("Renaming TV file: {} → {}", source, target);
                Files.move(source, target);
            } catch (Exception e) {
                LOGGER.error("Failed to rename file {} to {}: {}", source, target, e.getMessage());
            }
        }
    }

    /**
     * Determines the action to take for a file based on its presence in the database and file system.
     */
    private static String getCollectionAction(boolean existInDatabase, boolean existInPath) {
        if (existInDatabase && !existInPath) return "DELETE";
        if (!existInDatabase && existInPath) return "ADD";
        return "SKIP";
    }
}
