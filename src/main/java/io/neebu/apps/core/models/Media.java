package io.neebu.apps.core.models;

import io.neebu.apps.core.CollectionUtils;
import io.neebu.apps.proc.SourceParser;
import io.neebu.apps.pojos.CollectionType;
import io.neebu.apps.proc.MediaParser;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static io.neebu.apps.core.entities.Constants.TMDB_ID_PATTERN;

@Getter
public class Media {

    //Construction Args
    private Path absolutePath;
    private CollectionType collectionType;

    //file properties - common
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


    //file properties - movies
    private String releaseYear;

    //file properties - tvshows
    private String seasonNumber;
    private String episodeNumber;

    //tmdb properties
    @Setter
    private Integer tmdbId ;
    @Setter private String releaseDate;
    @Setter private String tmdbName;
    @Setter private String tmdbDescription;
    @Setter private String episodeName;
    @Setter private String episodeOverview;

    //mediainfo properties
    private String resolution;
    private String hdrFormat ;
    private String videoCodec;
    private String audioCodec;
    private String audioChannels;

    public Media(Path absolutePath, CollectionType collectionType) throws IOException {

        //constructor values
        this.absolutePath = absolutePath;
        this.collectionType = collectionType;

        //file parsed values
        this.folderName = absolutePath.getParent();
        this.baseName = FilenameUtils.getBaseName(absolutePath.toString());
        this.fileExtension = FilenameUtils.getExtension(absolutePath.toString());
        if(collectionType.equals(CollectionType.TV)) {
            this.name = CollectionUtils.detectTitleSeasonAndEpisdoe(baseName)[0];
        } else {
            this.name = CollectionUtils.detectCleanTitleAndYear(baseName)[0];
        }

        this.sourceType = SourceParser.parseMediaSource(baseName).toString();
        this.source = CollectionUtils.getStreamingSource(baseName);
        if(CollectionUtils.isREMUX(baseName)){
            this.sourceType = "REMUX";
            this.source = SourceParser.BLURAY.toString();
        }

        if(baseName.lastIndexOf("-")>-1) {
            this.groupName = baseName.substring(baseName.lastIndexOf("-") + 1).trim();
        } else {
            this.groupName = null;
        }

        this.fileSize = Files.size(absolutePath) ;

        Matcher matcher = TMDB_ID_PATTERN.matcher(baseName);
        if (matcher.find() && matcher.groupCount() >= 2) {
            this.fileTmdbId = matcher.group(2);
            this.hasTmdbId =true;
        }

        //file properties - TV
        if(collectionType.equals(CollectionType.TV)) {
            this.seasonNumber = CollectionUtils.detectTitleSeasonAndEpisdoe(baseName)[1];
            this.episodeNumber = CollectionUtils.detectTitleSeasonAndEpisdoe(baseName)[2];
        }

        //file properties - MOVIE
        if(collectionType.equals(CollectionType.MOVIE)) {
            this.releaseYear = CollectionUtils.detectCleanTitleAndYear(baseName)[1];
        }

        //media parser
        MediaParser mediaParser = new MediaParser(absolutePath);
        this.videoCodec = mediaParser.getVideoCodec();
        this.resolution = mediaParser.getVideoFormat();
        this.hdrFormat = mediaParser.getHdrFormat();
        this.audioCodec = mediaParser.getAudioCodec();
        this.audioChannels = mediaParser.getAudioChannels();
    }

    public String getNormalisedFileName(){
        String[] ren = new String[0];

        if(collectionType.equals(CollectionType.MOVIE)) {
            String movieTitle = CollectionUtils.removeIllegalCharacters(
                    tmdbName.equalsIgnoreCase("NOT_FOUND") ? name : tmdbName
            ).replaceAll(" ", ".");
            movieTitle = movieTitle.startsWith("Con.") ? movieTitle.replaceFirst("Con\\.", "Con") : movieTitle;

            ren = new String[]{
                    movieTitle,
                    releaseYear.toString(),
                    resolution,
                    source,
                    sourceType,
                    hdrFormat,
                    videoCodec,
                    audioCodec,
                    audioChannels,
                    "{tmdb-" + tmdbId.toString() + "}-" + (StringUtils.isBlank(groupName) ? "NOGRP" : groupName),
                    fileExtension
            };
        }

        if(collectionType.equals(CollectionType.TV)) {
            String tvTitle = CollectionUtils.removeIllegalCharacters(
                    tmdbName.equalsIgnoreCase("NOT_FOUND") ? name : tmdbName
            ).replaceAll(" ", ".");
            tvTitle = tvTitle.startsWith("Con.") ? tvTitle.replaceFirst("Con\\.", "Con") : tvTitle;

            ren = new String[]{
                    tvTitle,
                    "S" + seasonNumber + "E" + episodeNumber,
                    CollectionUtils.removeIllegalCharacters(episodeName).replaceAll(" ", "."),
                    resolution,
                    source,
                    sourceType,
                    audioCodec,
                    audioChannels,
                    hdrFormat,
                    videoCodec + "-" + (StringUtils.isBlank(groupName) ? "Ayumi" : groupName),
                    fileExtension
            };
        }

        return Arrays.stream(ren)
                .filter(s -> !StringUtils.isBlank(s)) // Filter out empty strings
                .collect(Collectors.joining(".")); // Join with comma and space
    }

    @Override
    public String toString() {
        return "Media{" +
                "absolutePath=" + absolutePath +
                ", folderName=" + folderName +
                ", baseName='" + baseName + '\'' +
                ", fileExtension='" + fileExtension + '\'' +
                ", name='" + name + '\'' +
                ", releaseYear='" + releaseYear + '\'' +
                ", seasonNumber='" + seasonNumber + '\'' +
                ", episodeNumber='" + episodeNumber + '\'' +
                ", sourceType='" + sourceType + '\'' +
                ", source='" + source + '\'' +
                ", groupName='" + groupName + '\'' +
                ", fileSize=" + fileSize +
                ", tmdbId=" + tmdbId +
                ", releaseDate='" + releaseDate + '\'' +
                ", tmdbName='" + tmdbName + '\'' +
                ", tmdbDescription='" + tmdbDescription + '\'' +
                ", episodeName='" + episodeName + '\'' +
                ", episodeOverview='" + episodeOverview + '\'' +
                ", resolution='" + resolution + '\'' +
                ", hdrFormat='" + hdrFormat + '\'' +
                ", videoCodec='" + videoCodec + '\'' +
                ", audioCodec='" + audioCodec + '\'' +
                ", audioChannels='" + audioChannels + '\'' +
                ", filenameHasTmdbId=" + hasTmdbId +
                '}';
    }
}
