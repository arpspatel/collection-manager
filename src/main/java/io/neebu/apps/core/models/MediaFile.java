package io.neebu.apps.core.models;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaFile.class.getName());

    private Path absolutePath;
    private Constants.CollectionType collectionType;

    private Path folderName;
    private String baseName;
    private String fileExtension;
    private String name;
    private String sourceType;
    private String source;
    private String groupName;
    private String fileTmdbId;
    private Long fileSize;
    private boolean hasTmdbId = false;

    private Integer releaseYear = null;

    private String seasonNumber;
    private String episodeNumber;

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

    private String resolution;
    private String hdrFormat;
    private String videoCodec;
    private String audioCodec;
    private String audioChannels;

    private Path normalizedTitle;
    private boolean renameRequired;

    public MediaFile(Path absolutePath, Constants.CollectionType collectionType) throws IOException {

        this.absolutePath = absolutePath;
        this.collectionType = collectionType;

        this.folderName = absolutePath.getParent();
        this.baseName = FilenameUtils.getBaseName(absolutePath.toString());
        this.fileExtension = FilenameUtils.getExtension(absolutePath.toString());

        MediaMetadata mediaMetadata = new MediaMetadata(baseName);
        this.name = mediaMetadata.getTitle();
        this.seasonNumber = mediaMetadata.getSeason();
        this.episodeNumber = mediaMetadata.getEpisode();
        this.releaseYear = StringUtils.isNumeric(mediaMetadata.getYear()) ? Integer.valueOf(mediaMetadata.getYear()) : null;
        this.sourceType = SourceParser.parseMediaSource(baseName).toString();

        this.source = CollectionUtils.getStreamingSource(baseName);


        if ((baseName.toUpperCase().contains("REMUX") || baseName.toUpperCase().contains(".BD50") ||
                baseName.toUpperCase().contains("BDMV"))) {
            this.sourceType = "REMUX";
            this.source = SourceParser.BLURAY.toString();
        }

        if (baseName.lastIndexOf("-") > -1) {
            this.groupName = baseName.substring(baseName.lastIndexOf("-") + 1).trim();
        } else {
            this.groupName = null;
        }

        this.fileSize = Files.size(absolutePath);

        Matcher matcher = TMDB_ID_PATTERN.matcher(baseName);
        if (matcher.find() && matcher.groupCount() >= 2) {
            this.fileTmdbId = matcher.group(2);
            this.hasTmdbId = true;
        }

        MediaParser mediaParser = new MediaParser(absolutePath);
        this.videoCodec = mediaParser.getVideoCodec();
        this.resolution = mediaParser.getVideoFormat();
        this.hdrFormat = mediaParser.getHdrFormat();
        this.audioCodec = mediaParser.getAudioCodec();
        this.audioChannels = mediaParser.getAudioChannels();
    }

    public void applyNamingConvention() {

        String cleanTitle = CollectionUtils.cleanString(tmdbName.equalsIgnoreCase("NOT_FOUND") ? name : tmdbName);
        cleanTitle = cleanTitle.startsWith("Con.") ? cleanTitle.replaceFirst("Con\\.", "Con") : cleanTitle;

        Stream<String> typeSpecificParts = collectionType.equals(Constants.CollectionType.MOVIE)
                ? Stream.of(releaseYear != null ? releaseYear.toString() : null)
                : Stream.of("S" + seasonNumber + "E" + episodeNumber, CollectionUtils.cleanString(episodeName));

        Stream<String> codecParts = collectionType.equals(Constants.CollectionType.MOVIE)
                ? Stream.of(hdrFormat, videoCodec, audioCodec, audioChannels)
                : Stream.of(audioCodec, audioChannels, hdrFormat, videoCodec);

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


//        // Helper to clean and dot-replace title
//        String cleanTitle = CollectionUtils.removeIllegalCharacters(
//                tmdbName.equalsIgnoreCase("NOT_FOUND") ? name : tmdbName
//        ).replace(" ", ".");
//        cleanTitle = cleanTitle.startsWith("Con.") ? cleanTitle.replaceFirst("Con\\.", "Con") : cleanTitle;
//
//        List<String> parts = new ArrayList<>();
//
//        parts.add(cleanTitle);
//
//        if (collectionType.equals(Constants.CollectionType.MOVIE)) {
//            parts.add(releaseYear != null ? releaseYear.toString() : null);
//        } else if (collectionType.equals(Constants.CollectionType.TV)) {
//            parts.add("S" + seasonNumber + "E" + episodeNumber);
//            parts.add(CollectionUtils.removeIllegalCharacters(episodeName).replace(" ", "."));
//        }
//
//        parts.add(resolution);
//        parts.add(source);
//        parts.add(sourceType);
//
//        if (collectionType.equals(Constants.CollectionType.MOVIE)) {
//            parts.add(hdrFormat);
//            parts.add(videoCodec);
//            parts.add(audioCodec);
//            parts.add(audioChannels);
//            //parts.add("{tmdb-" + tmdbId + "}-" + (StringUtils.isBlank(groupName) ? "Ayumi" : groupName));
//        } else if (collectionType.equals(Constants.CollectionType.TV)) {
//            parts.add(audioCodec);
//            parts.add(audioChannels);
//            parts.add(hdrFormat);
//            parts.add(videoCodec); // + "-" + (StringUtils.isBlank(groupName) ? "Ayumi" : groupName));
//        }
//
//        parts.add("{tmdb-" + tmdbId + "}-" + (StringUtils.isBlank(groupName) ? "Ayumi" : groupName));
//
//        parts.add(fileExtension);
//
//        this.normalizedFilename = absolutePath.resolveSibling(
//                parts.stream()
//                        .filter(StringUtils::isNotBlank)
//                        .collect(Collectors.joining("."))
//        ).toAbsolutePath();

        this.renameRequired = !this.absolutePath.equals(this.normalizedTitle);
    }
}
