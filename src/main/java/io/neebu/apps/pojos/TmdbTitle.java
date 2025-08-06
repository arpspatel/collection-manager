package io.neebu.apps.pojos;


import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TmdbTitle {
    private Integer tmdbId;
    private String tmdbName;
    private String releaseDate;
    private String tmdbDescription;
}
