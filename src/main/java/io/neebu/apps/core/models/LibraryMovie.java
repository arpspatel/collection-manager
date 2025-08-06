package io.neebu.apps.core.models;

import io.neebu.apps.core.CollectionUtils;
import io.neebu.apps.proc.SourceParser;
import io.neebu.apps.pojos.TmdbMovie;
import io.neebu.apps.proc.MediaParser;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.ToString;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

@Deprecated
@Getter
@ToString
public class LibraryMovie {

    private Path filePath;
    private String baseName;
    private String fileExtension;
    private String movieName;
    private Integer movieYear;
    private String sourceType;
    private String source;
    private String groupName;
    private Long fileSize;
    private String resolution;
    private String hdrFormat ;
    private String videoCodec;
    private String audioCodec;
    private String audioChannels;
    private TmdbMovie tmdbMovie;

    @SneakyThrows
    public LibraryMovie(Path filePath, String apiKey){
        this.filePath = filePath;
        this.baseName = FilenameUtils.getBaseName(filePath.toString());
        this.fileExtension = FilenameUtils.getExtension(filePath.toString());
        this.movieName = CollectionUtils.detectCleanTitleAndYear(baseName)[0];
        this.movieYear = Integer.valueOf(CollectionUtils.detectCleanTitleAndYear(baseName)[1]);
        this.sourceType = SourceParser.parseMediaSource(baseName).toString();
        this.source = CollectionUtils.getStreamingSource(baseName);
        if(CollectionUtils.isREMUX(baseName)){
            this.sourceType = "REMUX";
            this.source = SourceParser.BLURAY.toString();
        }
        this.tmdbMovie = CollectionUtils.detectTmdbId(baseName,apiKey);
        this.groupName = baseName.substring(baseName.lastIndexOf("-")+1).trim();
        this.fileSize = Files.size(filePath) ;

        MediaParser mediaParser = new MediaParser(filePath);
        this.videoCodec = mediaParser.getVideoCodec();
        this.resolution = mediaParser.getVideoFormat();
        this.hdrFormat = mediaParser.getHdrFormat();
        this.audioCodec = mediaParser.getAudioCodec();
        this.audioChannels = mediaParser.getAudioChannels();
    }

    public String getRenamedFormat(){
        String movieTitle = CollectionUtils.removeIllegalCharacters(
                tmdbMovie.getTmdbName().equalsIgnoreCase("NOT_FOUND")?movieName:tmdbMovie.getTmdbName()
        ).replaceAll(" ",".");
        movieTitle = movieTitle.startsWith("Con.")?movieTitle.replaceFirst("Con\\.","Con"):movieTitle;

        String[] ren = new String[]{
                movieTitle,
                movieYear.toString(),
                resolution,
                source,
                sourceType,
                hdrFormat,
                videoCodec,
                audioCodec,
                audioChannels,
                "{tmdb-"+tmdbMovie.getTmdbId().toString()+"}-"+ (StringUtils.isBlank(groupName)?"NOGRP":groupName),
                fileExtension
        };

        return Arrays.stream(ren)
                .filter(s -> !StringUtils.isBlank(s)) // Filter out empty strings
                .collect(Collectors.joining(".")); // Join with comma and space
    }

}
