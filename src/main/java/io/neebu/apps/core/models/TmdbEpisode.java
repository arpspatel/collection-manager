package io.neebu.apps.core.models;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TmdbEpisode {
    private String episodeNumber;
    private String seasonNumber;
    private String releaseDate;
    private String name;
    private String overview;
}
