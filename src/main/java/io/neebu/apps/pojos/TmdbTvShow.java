package io.neebu.apps.pojos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TmdbTvShow {
    private Integer tmdbId;
    private String name;
    private String firstAirDate;
}