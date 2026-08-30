package io.neebu.apps.core.models;

// MediaFile represents a media file (movie or TV episode) and extracts metadata from its filename and path.
// It provides methods to parse, normalize, and rename files according to conventions.

import io.neebu.apps.utils.CollectionUtils;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.utils.MediaMetadata;
import io.neebu.apps.utils.SourceParser;
import io.neebu.apps.utils.MediaParser;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.neebu.apps.core.entities.Constants.*;

@Getter
@ToString
public class MediaFile {

    // Logger for debugging and info
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaFile.class.getName());

    // Absolute path to the media file - not final: updated after a successful on-disk rename
    // so the DB insert (which happens after renaming) records where the file actually ended up.
    private Path absolutePath;
    // Type of collection (MOVIE or TV)
    private final Constants.CollectionType collectionType;

    // Parent folder name
    private final Path folderName;
    // Base name of the file (without extension)
    private final String baseName;
    // File extension (e.g., mkv, mp4)
    private final String fileExtension;
    // Parsed title from filename
    private final String name;
    // Source type (e.g., WEB, REMUX)
    private final String sourceType;
    // Streaming source (e.g., Netflix, Amazon)
    private final String source;
    // Release group name
    private final String groupName;
    // TMDB ID found in filename
    private final String fileTmdbId;
    // File size in bytes
    private final Long fileSize;
    // True if TMDB ID is present in filename
    private final boolean hasTmdbId;

    // Release year (parsed from filename)
    private final Integer releaseYear;

    // Season and episode numbers (for TV)
    private final String seasonNumber;
    private final String episodeNumber;

    @Setter
    private Integer tmdbId;
    @Setter
    private String releaseDate;
    @Setter
    private String tmdbName;
    @Setter
    private String tmdbDescription;
    @Setter
    private String episodeName;
    @Setter
    private String episodeOverview;

    // Video and audio metadata
    private final String resolution;
    private final String hdrFormat;
    private final String videoCodec;
    private final String audioCodec;
    private final String audioChannels;

    // Normalized title/path for renaming
    private Path normalizedTitle;
    // True if renaming is required
    private boolean renameRequired;

    /**
     * Constructs a MediaFile and parses metadata from the filename and path.
     * @param absolutePath Absolute path to the media file
     * @param collectionType Type of collection (MOVIE or TV)
     * @param mediaInfoExecutable Path/command used to invoke the mediainfo CLI
     * @throws IOException if file size cannot be read
     */
    public MediaFile(Path absolutePath, Constants.CollectionType collectionType, String mediaInfoExecutable) throws IOException {
        this.absolutePath = absolutePath;
        this.collectionType = collectionType;
        this.folderName = absolutePath.getParent();
        this.baseName = FilenameUtils.getBaseName(absolutePath.toString());
        this.fileExtension = FilenameUtils.getExtension(absolutePath.toString());

        // Parse metadata from filename
        MediaMetadata mediaMetadata = new MediaMetadata(baseName);
        this.name = mediaMetadata.getTitle();
        this.seasonNumber = CollectionUtils.zeroPad(mediaMetadata.getSeason(), 2);
        this.episodeNumber = CollectionUtils.zeroPad(mediaMetadata.getEpisode(), 2);
        this.releaseYear = StringUtils.isNumeric(mediaMetadata.getYear()) ? Integer.valueOf(mediaMetadata.getYear()) : null;

        // Use local variables for sourceType and source
        String tempSourceType = SourceParser.parseMediaSource(baseName).toString();
        String tempSource = CollectionUtils.getStreamingSource(baseName);

        // Special handling for BluRay/Remux sources
        if ((baseName.toUpperCase().contains("REMUX") || baseName.toUpperCase().contains(".BD50") ||
                baseName.toUpperCase().contains("BDMV"))) {
            tempSourceType = "REMUX";
            tempSource = SourceParser.BLURAY.toString();
            LOGGER.debug("Detected BluRay/Remux source for file: {}", baseName);
        }
        this.sourceType = tempSourceType;
        this.source = tempSource;

        // Extract release group name
        if (baseName.lastIndexOf("-") > -1) {
            this.groupName = baseName.substring(baseName.lastIndexOf("-") + 1).trim();
        } else {
            this.groupName = null;
        }

        // Get file size
        this.fileSize = Files.size(absolutePath);
        LOGGER.debug("File size for {}: {} bytes", baseName, fileSize);

        // Extract TMDB ID from filename using regex
        Matcher matcher = TMDB_ID_PATTERN.matcher(baseName);
        if (matcher.find() && matcher.groupCount() >= 2) {
            this.fileTmdbId = matcher.group(2);
            this.hasTmdbId = true;
            LOGGER.info("TMDB ID found in filename: {}", fileTmdbId);
        } else {
            this.fileTmdbId = null;
            this.hasTmdbId = false;
        }

        // Parse video/audio metadata from file
        try (MediaParser mediaParser = new MediaParser(absolutePath, mediaInfoExecutable)) {
            this.videoCodec = mediaParser.getVideoCodec();
            this.resolution = mediaParser.getVideoFormat();
            this.hdrFormat = mediaParser.getHdrFormat();
            this.audioCodec = mediaParser.getAudioCodec();
            this.audioChannels = mediaParser.getAudioChannels();
        }
        LOGGER.debug("Parsed media codecs for {}: video={}, audio={}, channels={}", baseName, videoCodec, audioCodec, audioChannels);
    }

    /**
     * Builds a normalized filename/path according to naming conventions and sets renameRequired flag.
     * Uses TMDB/episode metadata if available.
     */
    public void applyNamingConvention() {
        // Use TMDB name if available, else fallback to parsed name
        String cleanTitle = CollectionUtils.cleanString(tmdbName != null && tmdbName.equalsIgnoreCase("NOT_FOUND") ? name : tmdbName);
        if (cleanTitle != null && cleanTitle.startsWith("Con.")) {
            cleanTitle = cleanTitle.replaceFirst("Con\\.", "Con");
        }

        // Build type-specific parts (movie: year, TV: season/episode)
        String seasonEpisodeTag = StringUtils.isNotBlank(seasonNumber) && StringUtils.isNotBlank(episodeNumber)
                ? "S" + seasonNumber + "E" + episodeNumber : null;
        Stream<String> typeSpecificParts = collectionType.equals(Constants.CollectionType.MOVIE)
                ? Stream.of(releaseYear != null ? releaseYear.toString() : null)
                : Stream.of(seasonEpisodeTag, CollectionUtils.cleanString(episodeName));

        // Build codec parts for filename
        Stream<String> codecParts = Stream.of(hdrFormat, videoCodec, audioCodec, audioChannels);

        // Compose normalized title/path
        this.normalizedTitle = absolutePath.resolveSibling(
                Stream.of(
                                Stream.of(cleanTitle),
                                typeSpecificParts,
                                Stream.of(resolution, source, sourceType),
                                codecParts,
                                Stream.of("{tmdb-" + tmdbId + "}-" + (StringUtils.isBlank(groupName) ? "Ayumi" : groupName), fileExtension)
                        )
                        .flatMap(s -> s)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.joining("."))
        ).toAbsolutePath();

        LOGGER.info("Normalized title: {}", normalizedTitle);

        // Set flag if renaming is required
        this.renameRequired = !this.absolutePath.equals(this.normalizedTitle);
        if (renameRequired) {
            LOGGER.info("File {} requires renaming to {}", absolutePath, normalizedTitle);
        } else {
            LOGGER.debug("File {} does not require renaming.", absolutePath);
        }
    }

    /**
     * Updates the tracked path after a successful on-disk move/rename.
     */
    public void updateAbsolutePath(Path newPath) {
        this.absolutePath = newPath;
    }
}
