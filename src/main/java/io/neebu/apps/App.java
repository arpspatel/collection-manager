package io.neebu.apps;

import io.neebu.apps.conn.DatabaseApp;
import io.neebu.apps.conn.TmdbApiClient;
import io.neebu.apps.core.AppProperties;
import io.neebu.apps.core.CollectionManager;
import io.neebu.apps.core.CollectionUtils;
import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.LibraryMovie;
import io.neebu.apps.core.models.Media;
import io.neebu.apps.pojos.CollectionType;
import io.neebu.apps.pojos.TmdbEpisode;
import io.neebu.apps.pojos.TmdbTitle;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class App {

    private static final Logger LOGGER = LoggerFactory.getLogger(App.class.getName());

    @SneakyThrows
    public static void main(String[] args) {
        AppProperties appProperties = new AppProperties();

        List<String> tvShowFiles = appProperties.getTvShowPaths().stream()
                .flatMap(folderPath -> CollectionUtils.listFilesRecursively(Paths.get(folderPath).toAbsolutePath()).stream())
                .filter(path -> !Files.isDirectory(path))
                .map(path -> path.toAbsolutePath().toString())
                .toList();

        if (!tvShowFiles.isEmpty()) {
            DatabaseApp databaseApp = new DatabaseApp();
            databaseApp.connect(appProperties.getDatabaseUrl(), appProperties.getDatabaseUser(), appProperties.getDatabasePass());
            List<String> tvCollection = databaseApp.getCollection(Constants.SELECT_TV_SQL);
            HashMap<String, String> tvShowFileActionMap = new HashMap<>();
            for(String filePath: Stream.of(tvShowFiles, tvCollection).flatMap(Collection::stream).distinct().toList()){
                tvShowFileActionMap.put(filePath,CollectionUtils.getCollectionAction(filePath,tvCollection.contains(filePath),tvShowFiles.contains(filePath)));
            }
            runApp(CollectionType.TV, appProperties, tvShowFileActionMap);
        }

        List<String> movieFiles = appProperties.getMoviePaths().stream()
                .flatMap(folderPath -> CollectionUtils.listFilesRecursively(Paths.get(folderPath).toAbsolutePath()).stream())
                .filter(path -> !Files.isDirectory(path))
                .map(path -> path.toAbsolutePath().toString())
                .toList();

        if (!movieFiles.isEmpty()) {
            runApp(CollectionType.MOVIE, appProperties, movieFiles);
        }



//      Pass 1 to update and rename records
//        updateCollection(appProperties);
//      Pass 2 to update renamed data
//        updateCollection(appProperties);
    }

    public static void runApp(CollectionType collectionType, AppProperties appProperties, HashMap<String, String> fileActionMap) throws Exception {

        HashMap<String, TmdbTitle> titleMap = new HashMap<>();
        for (String filename : fileActionMap.keySet()) {
            if(fileActionMap.get(filename).equals("ADD")) {
                Media mediaFile = new Media(Paths.get(filename), collectionType);

                if (!titleMap.containsKey(mediaFile.getName())) {
                    TmdbTitle tmdbTitle = CollectionUtils.getTmdbTitleInfo(collectionType, appProperties, mediaFile);
                    titleMap.put(mediaFile.getName(), tmdbTitle);
                }

                mediaFile.setTmdbId(titleMap.get(mediaFile.getName()).getTmdbId());
                mediaFile.setTmdbName(titleMap.get(mediaFile.getName()).getTmdbName());
                mediaFile.setReleaseDate(titleMap.get(mediaFile.getName()).getReleaseDate());
                mediaFile.setTmdbDescription(titleMap.get(mediaFile.getName()).getTmdbDescription());

                if (collectionType.equals(CollectionType.TV)) {
                    // Get episode details
                    TmdbEpisode ep = TmdbApiClient.getTvShowEpisodeInfo(appProperties.getTmdbApiKey(), appProperties.getTmdbApiUri(), mediaFile.getTmdbId(), mediaFile.getSeasonNumber(), mediaFile.getEpisodeNumber());
                    mediaFile.setEpisodeName(ep.getName());
                    mediaFile.setEpisodeOverview(ep.getOverview());
                }
                System.out.println(mediaFile.getAbsolutePath() + "->" + mediaFile.getNormalisedFileName());
            }
            // DELETE - Add Code for it.
            // SKIP - just counter
        }
    }


    private static void updateCollection(AppProperties appProperties) throws IOException {
        List<String> allFiles = appProperties.getMoviePaths().stream().flatMap(
                folderPath -> CollectionUtils.listFilesAndDirs(Paths.get(folderPath).toAbsolutePath()).stream()).filter(path -> !Files.isDirectory(path)).map(path -> path.toAbsolutePath().toString()).collect(Collectors.toList()
        );

        if (!allFiles.isEmpty()) {
            DatabaseApp databaseApp = new DatabaseApp();
            databaseApp.connect(appProperties.getDatabaseUrl(), appProperties.getDatabaseUser(), appProperties.getDatabasePass());
            List<String> moviesDb = databaseApp.getMovies();
            List<CollectionManager> fileList = new ArrayList<>();
            for (String filePath : Stream.of(allFiles, moviesDb).flatMap(Collection::stream).distinct().toList()) {
                CollectionManager collectionManager = new CollectionManager(filePath, moviesDb.contains(filePath), allFiles.contains(filePath));
                if (!collectionManager.getAddOrDeleteOrSkip().equalsIgnoreCase("SKIP")) {
                    LOGGER.info("Action : {}, Filename :  {}", collectionManager.getAddOrDeleteOrSkip(), collectionManager.getFileName());
                }
//                if (!fileParser.getFileName().contains("Thug")) {
                if (collectionManager.getAddOrDeleteOrSkip().equalsIgnoreCase("DELETE")) {
//                    LOGGER.info("skipping delete to verify database :  {}",fileParser.getFileName());
                    databaseApp.delete(collectionManager.getFileName());
                }
                if (collectionManager.getAddOrDeleteOrSkip().equalsIgnoreCase("ADD")) {
                    LibraryMovie libraryMovie = new LibraryMovie(Paths.get(collectionManager.getFileName()), appProperties.getTmdbApiKey());
                    collectionManager.setNewFileName(libraryMovie.getRenamedFormat());
                    databaseApp.insert(libraryMovie);
                }
                if (collectionManager.getRenameRequired()) {
                    Path source = Paths.get(collectionManager.getFileName());
                    Files.move(source, source.resolveSibling(collectionManager.getNewFileName()));
                }
                fileList.add(collectionManager);
                LOGGER.trace(collectionManager.toString());
//                }
            }
            databaseApp.close();
            LOGGER.info("Summary: {}, Renamed={}", CollectionUtils.getAddDeleteSkipSummary(fileList), CollectionUtils.getRenamedSummary(fileList));
            LOGGER.info("Completed.");
        } else {
            LOGGER.info("Folders empty ? are the paths correct ?");
        }
    }
}
