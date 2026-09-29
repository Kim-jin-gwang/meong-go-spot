package com.meonggo.backend.photo.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.global.error.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WebHdfsPhotoStorageTest {
    private HttpServer server;
    private String origin;
    private WebHdfsPhotoStorage storage;
    private final List<String> requests = new ArrayList<>();
    private String redirect;
    private int status = 201;
    private boolean collision;
    private String listing = "{\"FileStatuses\":{\"FileStatus\":[]}}";
    private byte[] received;
    private static final String PATH = "/data/user/images/1/2.jpg";

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        storage =
                new WebHdfsPhotoStorage(
                        URI.create(origin), Set.of(URI.create(origin)), "app", new JsonMapper());
        server.createContext(
                "/",
                exchange -> {
                    String query = exchange.getRequestURI().getRawQuery();
                    requests.add(exchange.getRequestMethod() + " " + query);
                    byte[] response = "{\"boolean\":true}".getBytes(StandardCharsets.UTF_8);
                    int code = 200;
                    if (query.contains("LISTSTATUS")) {
                        response = listing.getBytes(StandardCharsets.UTF_8);
                    } else if (query.contains("GETFILESTATUS")) {
                        code = collision ? 200 : 404;
                    } else if (query.contains("CREATE") || query.contains("OPEN")) {
                        if (!query.contains("node=1")) {
                            assertThat(exchange.getRequestBody().readAllBytes()).isEmpty();
                            exchange.getResponseHeaders()
                                    .add(
                                            "Location",
                                            redirect == null
                                                    ? origin + exchange.getRequestURI() + "&node=1"
                                                    : redirect);
                            code = 307;
                        } else {
                            received = exchange.getRequestBody().readAllBytes();
                            code = query.contains("OPEN") ? 200 : status;
                            response = new byte[] {1, 2, 3};
                        }
                    }
                    exchange.sendResponseHeaders(code, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void teardown() {
        server.stop(0);
    }

    @Test
    void createsWithSafePolicyAndTransfersBytesOnlyAfterRedirect() {
        storage.write(PATH, new byte[] {4, 5});
        assertThat(received).containsExactly(4, 5);
        assertThat(requests).anyMatch(s -> s.contains("MKDIRS") && s.contains("permission=750"));
        assertThat(requests)
                .filteredOn(s -> s.contains("CREATE"))
                .allMatch(
                        s ->
                                s.contains("replication=2")
                                        && s.contains("overwrite=false")
                                        && s.contains("permission=600"));
    }

    @Test
    void opensAndClosesBinaryStream() throws Exception {
        try (var stream = storage.open(PATH)) {
            assertThat(stream.readAllBytes()).containsExactly(1, 2, 3);
        }
    }

    @Test
    void rejectsRedirectsOutsideExactOriginPathAndPolicy() {
        for (String location :
                List.of(
                        "http://127.0.0.1:1/webhdfs/v1" + PATH + "?op=CREATE",
                        origin + "/webhdfs/v1/data/shelter/1?op=CREATE",
                        origin + "/webhdfs/v1" + PATH + "?op=CREATE&overwrite=true",
                        origin + "/webhdfs/v1" + PATH + "?op=OPEN",
                        origin + "/webhdfs/v1" + PATH + "?op=CREATE&replication=1",
                        origin.replace("http://", "http://secret@")
                                + "/webhdfs/v1"
                                + PATH
                                + "?op=CREATE")) {
            redirect = location;
            assertThatThrownBy(() -> storage.write(PATH, new byte[] {9}))
                    .isInstanceOf(BusinessException.class)
                    .hasCause(null)
                    .hasMessageNotContaining("secret");
        }
        assertThat(received).isNull();
    }

    @Test
    void rejectsUnownedPathsBeforeNetwork() {
        for (String path :
                List.of(
                        "/data/shelter/images/1.jpg",
                        "/data/user/images/../1/2.jpg",
                        "/data/user/images/0/2.jpg",
                        "/data/user/images/9223372036854775808/2.jpg")) {
            assertThatThrownBy(() -> storage.delete(path)).isInstanceOf(BusinessException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void renameCollisionNeverSendsRename() {
        collision = true;
        assertThatThrownBy(
                        () ->
                                storage.move(
                                        "/data/user/images/.staging/123e4567-e89b-12d3-a456-426614174000/2.jpg",
                                        PATH))
                .isInstanceOf(BusinessException.class);
        assertThat(requests).noneMatch(s -> s.contains("RENAME"));
    }

    @Test
    void sanitizesFailedWrite() {
        status = 500;
        assertThatThrownBy(() -> storage.write(PATH, new byte[] {9}))
                .isInstanceOf(BusinessException.class)
                .hasCause(null)
                .hasMessageNotContaining(PATH);
    }

    @Test
    void listingIgnoresSymlinksUnexpectedNamesAndMissingTimestamps() {
        listing =
                """
            {"FileStatuses":{"FileStatus":[
            {"pathSuffix":"1.jpg","type":"FILE","modificationTime":1},
            {"pathSuffix":"2.jpg","type":"SYMLINK","modificationTime":1},
            {"pathSuffix":"../3.jpg","type":"FILE","modificationTime":1},
            {"pathSuffix":"4.jpg","type":"FILE"},
            {"pathSuffix":"9223372036854775808.jpg","type":"FILE","modificationTime":1}
            ]}}
            """;
        assertThat(storage.list("/data/user/images/1"))
                .extracting(PhotoStorage.Entry::path)
                .containsExactly("/data/user/images/1/1.jpg");
    }

    @Test
    void oversizedMetadataFailsWithoutEchoingResponse() {
        listing = "x".repeat(2 * 1024 * 1024 + 1);
        assertThatThrownBy(() -> storage.list("/data/user/images/1"))
                .isInstanceOf(BusinessException.class)
                .hasCause(null);
    }

    @Test
    void deletesOnlyNonRecursivelyAndMovesWithinOwnedNamespace() {
        storage.delete(PATH);
        storage.move("/data/user/images/.staging/123e4567-e89b-12d3-a456-426614174000/2.jpg", PATH);
        assertThat(requests)
                .anyMatch(s -> s.startsWith("DELETE ") && s.contains("recursive=false"));
        assertThat(requests)
                .anyMatch(
                        s ->
                                s.contains("RENAME")
                                        && s.contains(
                                                "destination=%2Fdata%2Fuser%2Fimages%2F1%2F2.jpg"));
    }
}
