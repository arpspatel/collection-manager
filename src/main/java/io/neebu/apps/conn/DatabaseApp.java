package io.neebu.apps.conn;

import io.neebu.apps.core.entities.Constants;
import io.neebu.apps.core.models.MediaFile;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DatabaseApp provides methods to interact with the media database.
 * It supports connecting, inserting, deleting, and querying media records.
 */
public class DatabaseApp implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseApp.class.getName());

    private Connection conn;

    /**
     * Establishes a connection to the database.
     * @param url JDBC URL
     * @param user Username
     * @param pass Password
     */
    @SneakyThrows
    public void connect(String url, String user, String pass){
        LOGGER.info("Connecting to database: {}", url);
        conn = DriverManager.getConnection(url,user,pass);
        LOGGER.info("Database connection established.");
    }

    /**
     * Closes the database connection.
     */
    @SneakyThrows
    @Override
    public void close(){
        if (conn != null && !conn.isClosed()) {
            LOGGER.info("Closing database connection.");
            conn.close();
            LOGGER.info("Database connection closed.");
        }
    }

    /**
     * Retrieves a list of file paths from the database using the provided SQL query.
     * @param collectionSql SQL query to fetch collection
     * @return List of file paths
     */
    @SneakyThrows
    public List<String> getCollection(String collectionSql){
        List<String> moviesDb = new ArrayList<>();
        LOGGER.debug("Executing collection query: {}", collectionSql);
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(collectionSql)) {
            while (rs.next()) {
                moviesDb.add(rs.getString("FILE_PATH"));
            }
        } catch (SQLException e) {
            LOGGER.error("Error executing collection query", e);
            throw e;
        }
        return moviesDb;
    }

    /**
     * Inserts MediaFile records into the database in batches, committing once at the end.
     * @param mediaFiles MediaFile objects to insert
     */
    @SneakyThrows
    public void insertBatch(List<MediaFile> mediaFiles){
        if (mediaFiles.isEmpty()) {
            return;
        }
        LOGGER.debug("Batch inserting {} MediaFile record(s)", mediaFiles.size());
        try (PreparedStatement statement = conn.prepareStatement(Constants.INSERT_MEDIA_SQL)) {
            int pending = 0;
            for (MediaFile mediaFile : mediaFiles) {
                bindMediaFile(statement, mediaFile);
                statement.addBatch();
                if (++pending == Constants.JDBC_BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
            if (!conn.getAutoCommit()) {
                conn.commit();
            }
            LOGGER.info("Inserted {} record(s)", mediaFiles.size());
        } catch (SQLException e) {
            LOGGER.error("Error batch inserting {} MediaFile record(s)", mediaFiles.size(), e);
            throw e;
        }
    }

    /**
     * Updates ABSOLUTE_PATH for records whose file was renamed outside the normal insert flow
     * (e.g. an episode-padding repad touching files already catalogued from a prior run).
     * @param oldToNewPath Map of previous absolute path to new absolute path.
     */
    @SneakyThrows
    public void updatePaths(Map<String, String> oldToNewPath) {
        if (oldToNewPath.isEmpty()) {
            return;
        }
        LOGGER.debug("Batch updating {} path(s)", oldToNewPath.size());
        try (PreparedStatement statement = conn.prepareStatement(Constants.UPDATE_PATH_SQL)) {
            int pending = 0;
            for (Map.Entry<String, String> entry : oldToNewPath.entrySet()) {
                statement.setString(1, entry.getValue());
                statement.setString(2, entry.getKey());
                statement.addBatch();
                if (++pending == Constants.JDBC_BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
            if (!conn.getAutoCommit()) {
                conn.commit();
            }
            LOGGER.info("Updated {} path(s)", oldToNewPath.size());
        } catch (SQLException e) {
            LOGGER.error("Error batch updating {} path(s)", oldToNewPath.size(), e);
            throw e;
        }
    }

    private static void bindMediaFile(PreparedStatement statement, MediaFile mediaFile) throws SQLException {
        statement.setString(1,mediaFile.getCollectionType().toString());
        statement.setString(2,mediaFile.getAbsolutePath().toString());
        statement.setString(3,mediaFile.getBaseName());
        statement.setString(4,mediaFile.getFileExtension());
        statement.setString(5,mediaFile.getName());
        statement.setString(6,mediaFile.getSourceType());
        statement.setString(7,mediaFile.getSource());
        statement.setString(8,mediaFile.getGroupName());
        statement.setString(9,mediaFile.getTmdbId().toString());
        if(mediaFile.getReleaseYear()==null) { statement.setNull(10, Types.INTEGER); } else {statement.setInt(10, mediaFile.getReleaseYear()); }
        statement.setLong(11,mediaFile.getFileSize());
        statement.setString(12,mediaFile.getReleaseDate());
        statement.setString(13,mediaFile.getTmdbName());
        statement.setString(14,mediaFile.getTmdbDescription());
        statement.setString(15,mediaFile.getSeasonNumber());
        statement.setString(16,mediaFile.getEpisodeNumber());
        statement.setString(17,mediaFile.getEpisodeName());
        statement.setString(18,mediaFile.getEpisodeOverview());
        statement.setString(19,mediaFile.getResolution());
        statement.setString(20,mediaFile.getHdrFormat());
        statement.setString(21,mediaFile.getVideoCodec());
        statement.setString(22,mediaFile.getAudioCodec());
        statement.setString(23,mediaFile.getAudioChannels());
    }

    /**
     * Deletes a media record from the database by file path.
     * @param filePath Absolute file path to delete
     */
    @SneakyThrows
    public void delete(String filePath){
        LOGGER.debug("Deleting MediaFile: {}", filePath);
        try (PreparedStatement statement = conn.prepareStatement(Constants.DELETE_MEDIA_SQL)) {
            statement.setString(1,filePath);
            statement.executeUpdate();
            if (!conn.getAutoCommit()) {
                conn.commit();
            }
            LOGGER.info("Deleted Record : {}",filePath);
        } catch (SQLException e) {
            LOGGER.error("Error deleting MediaFile: {}", filePath, e);
            throw e;
        }
    }

}
