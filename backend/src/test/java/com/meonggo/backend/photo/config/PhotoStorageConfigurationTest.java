package com.meonggo.backend.photo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.storage.WebHdfsPhotoStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class PhotoStorageConfigurationTest {
    @Test
    void missingConfigStartsWithUnavailableStorage() {
        var storage =
                new PhotoStorageConfiguration()
                        .photoStorage(new MockEnvironment(), new JsonMapper());
        assertThatThrownBy(() -> storage.open("/data/user/images/1/1.jpg"))
                .isInstanceOf(BusinessException.class)
                .hasCause(null);
    }

    @Test
    void validatesOriginsWithoutLeakingConfiguration() {
        var environment =
                new MockEnvironment()
                        .withProperty(
                                "PHOTO_HDFS_NAMENODE_URL", "http://private:secret@internal:9870")
                        .withProperty("PHOTO_HDFS_DATANODE_URLS", "http://internal:9864")
                        .withProperty("PHOTO_HDFS_USER", "app");
        assertThatThrownBy(
                        () ->
                                new PhotoStorageConfiguration()
                                        .photoStorage(environment, new JsonMapper()))
                .hasMessageNotContaining("secret")
                .hasCause(null);
    }

    @Test
    void buildsConfiguredAdapter() {
        var environment =
                new MockEnvironment()
                        .withProperty("PHOTO_HDFS_NAMENODE_URL", "http://localhost:9870")
                        .withProperty(
                                "PHOTO_HDFS_DATANODE_URLS",
                                "http://localhost:9864,http://other:9864")
                        .withProperty("PHOTO_HDFS_USER", "app");
        assertThat(new PhotoStorageConfiguration().photoStorage(environment, new JsonMapper()))
                .isInstanceOf(WebHdfsPhotoStorage.class);
    }
}
