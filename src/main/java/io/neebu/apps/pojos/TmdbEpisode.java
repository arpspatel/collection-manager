package io.neebu.apps.pojos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TmdbEpisode {
    private String episodeNumber;
    private String seasonNumber;
    private String name;
    private String overview;
}
