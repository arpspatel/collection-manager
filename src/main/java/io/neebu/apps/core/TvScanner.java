package io.neebu.apps.core;

import io.neebu.apps.conn.DatabaseApp;
import io.neebu.apps.conn.TmdbApiClient;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.MediaFile;
import io.neebu.apps.core.models.TmdbEpisode;
import io.neebu.apps.core.models.TmdbTitle;
import io.neebu.apps.utils.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
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

            List<String> addPaths = fileActionMap.entrySet().stream()
                    .filter(e -> "ADD".equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            // Only files that actually need to be added get parsed/probed at all - this is the
            // existing DB-vs-folder logic doing its job. Each file's MediaInfo probe is
            // independent of every other file's, so build them concurrently.
            List<String> skipDetails = new CopyOnWriteArrayList<>();
            List<MediaFile> filesToAdd = CollectionUtils.parallelMap(addPaths, Constants.SCAN_THREAD_POOL_SIZE,
                    filePath -> buildMediaFile(filePath, skipDetails, appProperties.getMediaInfoPath()));

            // Group the new files by series so each show is looked up on TMDb exactly
            // once this run, regardless of how many of its episodes are new, and
            // regardless of how many shows live under a single configured root.
            List<String> tvRoots = appProperties.getTvShowPaths();
            Map<String, List<MediaFile>> seriesGroups = filesToAdd.stream()
                    .collect(Collectors.groupingBy(mediaFile -> seriesKey(mediaFile, tvRoots),
                            LinkedHashMap::new, Collectors.toList()));

            LOGGER.info("Found {} new TV file(s) across {} distinct series", filesToAdd.size(), seriesGroups.size());

            // Each series group is independent of every other, so groups resolve/enrich
            // concurrently; within a group the series lookup still happens exactly once.
            List<GroupResult> groupResults = CollectionUtils.parallelMap(
                    new ArrayList<>(seriesGroups.entrySet()), Constants.SCAN_THREAD_POOL_SIZE,
                    group -> processSeriesGroup(appProperties, group.getKey(), group.getValue()));

            List<MediaFile> readyToInsert = new ArrayList<>();
            for (GroupResult result : groupResults) {
                readyToInsert.addAll(result.readyToInsert());
                skipDetails.addAll(result.skipReasons());
            }
            skipCount += skipDetails.size();

            // Rename before inserting, so the DB row records the file's final on-disk path
            // rather than its pre-rename scan-time path.
            for (MediaFile mediaFile : readyToInsert) {
                renameIfRequired(mediaFile, appProperties);
            }

            databaseApp.insertBatch(readyToInsert);
            int addCount = readyToInsert.size();

            if (appProperties.isRenameTv()) {
                repadEpisodeNumbersIfNeeded(readyToInsert, databaseApp);
            }

            LOGGER.info("TV scan complete. Added={}, Deleted={}, Skipped={}", addCount, deleteCount, skipCount);
            if (!skipDetails.isEmpty()) {
                LOGGER.warn("{} file(s) skipped due to errors or unresolved TMDb lookups:\n{}",
                        skipDetails.size(), String.join("\n", skipDetails));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to complete TV scan: {}", e.getMessage(), e);
        }
    }

    /**
     * Builds a MediaFile for a path flagged ADD, logging and skipping on failure
     * (e.g. unreadable file) rather than aborting the whole run. The failure reason
     * is also recorded in {@code skipDetails} so it can be surfaced in the end-of-run summary.
     */
    private static MediaFile buildMediaFile(String filePath, List<String> skipDetails, String mediaInfoExecutable) {
        try {
            return new MediaFile(Paths.get(filePath), Constants.CollectionType.TV, mediaInfoExecutable);
        } catch (Exception e) {
            LOGGER.error("Could not read media file {}: {}", filePath, e.getMessage(), e);
            skipDetails.add(filePath + ": could not read media file - " + e.getMessage());
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
     * Result of resolving and enriching one series group: files ready for insertion, and the
     * reasons for any files skipped because the series itself couldn't be resolved on TMDb (or
     * an individual episode failed while enriching).
     */
    private record GroupResult(List<MediaFile> readyToInsert, List<String> skipReasons) {}

    /**
     * Resolves a series' TMDb title once, then enriches every episode in the group with it plus
     * its own per-episode TMDb data. Runs as a single task per group so groups can be processed
     * concurrently while each series is still looked up exactly once.
     */
    private static GroupResult processSeriesGroup(AppProperties appProperties, String seriesKey, List<MediaFile> episodes) {
        TmdbTitle tmdbTitle = resolveSeriesTitle(appProperties, episodes);
        if (tmdbTitle == null) {
            LOGGER.warn("Skipping {} file(s) - could not resolve series '{}' on TMDb", episodes.size(), seriesKey);
            List<String> reasons = episodes.stream()
                    .map(mediaFile -> mediaFile.getAbsolutePath() + ": could not resolve series '" + seriesKey + "' on TMDb")
                    .toList();
            return new GroupResult(List.of(), reasons);
        }

        List<MediaFile> ready = new ArrayList<>();
        List<String> skipReasons = new ArrayList<>();
        for (MediaFile mediaFile : episodes) {
            try {
                LOGGER.info("Preparing new TV file: {}", mediaFile.getAbsolutePath());
                enrichMediaWithTitle(mediaFile, tmdbTitle);
                fetchAndEnrichEpisode(appProperties, mediaFile);
                ready.add(mediaFile);
            } catch (Exception e) {
                LOGGER.error("Error handling TV file {}: {}", mediaFile.getAbsolutePath(), e.getMessage(), e);
                skipReasons.add(mediaFile.getAbsolutePath() + ": " + e.getMessage());
            }
        }
        return new GroupResult(ready, skipReasons);
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
                mediaFile.updateAbsolutePath(target);
            } catch (Exception e) {
                LOGGER.error("Failed to rename file {} to {}: {}", source, target, e.getMessage());
            }
        }
    }

    /** Matches this tool's own "S<season>E<episode>" naming tag, capturing the episode digits. */
    private static final Pattern EPISODE_TAG_PATTERN = Pattern.compile("S\\d+E(\\d+)");

    /**
     * For every folder touched by this run's new episodes, checks whether the folder's current
     * episode count now calls for wider zero-padding (3 digits at 100+ episodes, 4 at 1000+) than
     * what the files already sitting there use, and if so, repads every recognized episode file
     * in that folder to match - keeping a season/show's numbering consistent as it grows, without
     * an extra TMDb call (the folder's own file count is the only input).
     */
    private static void repadEpisodeNumbersIfNeeded(List<MediaFile> addedFiles, DatabaseApp databaseApp) {
        Set<Path> touchedFolders = addedFiles.stream()
                .map(MediaFile::getFolderName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (Path folder : touchedFolders) {
            repadFolder(folder, databaseApp);
        }
    }

    private static void repadFolder(Path folder, DatabaseApp databaseApp) {
        List<Path> videoFiles;
        try (Stream<Path> stream = Files.list(folder)) {
            videoFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return Constants.VIDEO_EXTENSIONS.stream().anyMatch(fileName::endsWith);
                    })
                    .toList();
        } catch (Exception e) {
            LOGGER.warn("Could not list folder {} for episode repadding: {}", folder, e.getMessage());
            return;
        }

        int requiredWidth = videoFiles.size() >= 1000 ? 4 : videoFiles.size() >= 100 ? 3 : 2;

        Map<Path, Matcher> episodeTags = new LinkedHashMap<>();
        boolean needsRepad = false;
        for (Path file : videoFiles) {
            Matcher matcher = EPISODE_TAG_PATTERN.matcher(file.getFileName().toString());
            if (matcher.find()) {
                episodeTags.put(file, matcher);
                if (matcher.group(1).length() < requiredWidth) {
                    needsRepad = true;
                }
            }
        }
        if (!needsRepad) {
            return;
        }

        LOGGER.info("Repadding episode numbers in {} to {} digit(s) ({} file(s) in folder)", folder, requiredWidth, videoFiles.size());
        Map<String, String> pathUpdates = new LinkedHashMap<>();
        for (Map.Entry<Path, Matcher> entry : episodeTags.entrySet()) {
            Path source = entry.getKey();
            Matcher matcher = entry.getValue();
            String paddedEpisode = CollectionUtils.zeroPad(matcher.group(1), requiredWidth);
            if (paddedEpisode.equals(matcher.group(1))) {
                continue;
            }
            String newName = new StringBuilder(source.getFileName().toString())
                    .replace(matcher.start(1), matcher.end(1), paddedEpisode)
                    .toString();
            Path target = source.resolveSibling(newName);
            try {
                Files.move(source, target);
                pathUpdates.put(source.toString(), target.toString());
            } catch (Exception e) {
                LOGGER.error("Failed to repad {} to {}: {}", source, target, e.getMessage());
            }
        }

        databaseApp.updatePaths(pathUpdates);
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
