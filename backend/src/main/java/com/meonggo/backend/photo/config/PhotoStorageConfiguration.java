package com.meonggo.backend.photo.config;

import com.meonggo.backend.member.service.MemberPhotoErasureScheduler;
import com.meonggo.backend.member.service.MemberPhotoErasureService;
import com.meonggo.backend.photo.service.PhotoOrphanCleanup;
import com.meonggo.backend.photo.storage.PhotoStorage;
import com.meonggo.backend.photo.storage.UnavailablePhotoStorage;
import com.meonggo.backend.photo.storage.WebHdfsPhotoStorage;
import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class PhotoStorageConfiguration {
    @Bean
    public PhotoStorage photoStorage(Environment environment, JsonMapper mapper) {
        String nameNode = environment.getProperty("PHOTO_HDFS_NAMENODE_URL", "");
        String dataNodes = environment.getProperty("PHOTO_HDFS_DATANODE_URLS", "");
        String user = environment.getProperty("PHOTO_HDFS_USER", "");
        if (nameNode.isBlank() && dataNodes.isBlank() && user.isBlank())
            return new UnavailablePhotoStorage();
        try {
            Set<URI> origins =
                    Arrays.stream(dataNodes.split(",", -1))
                            .map(String::trim)
                            .map(URI::create)
                            .collect(Collectors.toSet());
            return new WebHdfsPhotoStorage(URI.create(nameNode), origins, user, mapper);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid photo storage configuration");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "PHOTO_CLEANUP_ENABLED", havingValue = "true")
    @EnableScheduling
    static class CleanupConfiguration {
        @Bean
        PhotoOrphanCleanup photoOrphanCleanup(PhotoStorage storage, JdbcTemplate jdbc) {
            if (!(storage instanceof WebHdfsPhotoStorage))
                throw new IllegalStateException("Photo cleanup requires configured storage");
            return new PhotoOrphanCleanup(
                    storage,
                    path ->
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS (SELECT 1 FROM animal_photo WHERE storage_uri = ?)",
                                            Boolean.class,
                                            path)),
                    Clock.systemUTC());
        }

        @Bean
        MemberPhotoErasureScheduler memberPhotoErasureScheduler(MemberPhotoErasureService service) {
            return new MemberPhotoErasureScheduler(service);
        }
    }
}
