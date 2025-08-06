package io.neebu.apps.pojos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TmdbMovie {
    private Integer tmdbId;
    private String tmdbName;
    private String releaseDate;
}
