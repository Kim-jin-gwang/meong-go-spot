package com.meonggo.backend.photo.storage;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class WebHdfsPhotoStorage implements PhotoStorage {
    private static final int JSON_LIMIT = 2 * 1024 * 1024;
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "photo-storage-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final URI nameNode;
    private final Set<URI> dataNodes;
    private final String user;
    private final JsonMapper mapper;

    public WebHdfsPhotoStorage(URI nameNode, Set<URI> dataNodes, String user, JsonMapper mapper) {
        this.nameNode = origin(nameNode);
        this.dataNodes = new HashSet<>();
        dataNodes.forEach(node -> this.dataNodes.add(origin(node)));
        if (dataNodes.isEmpty() || user == null || !user.matches("[A-Za-z_][A-Za-z0-9_.-]{0,63}"))
            throw unavailable();
        this.user = user;
        this.mapper = mapper;
    }

    public static URI origin(URI uri) {
        if (uri == null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                || uri.getPort() == 0
                || uri.getPort() > 65535) throw unavailable();
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    @Override
    public void write(String path, byte[] bytes) {
        requireFile(path);
        mkdir(path.substring(0, path.lastIndexOf('/')));
        URI endpoint = endpoint(path, "CREATE", "&overwrite=false&replication=2&permission=600");
        URI target = redirect(endpoint, "PUT", path, "CREATE");
        try (Response response = request(target, "PUT", bytes)) {
            if (response.code() != 201) throw unavailable();
        }
    }

    @Override
    public void move(String source, String destination) {
        requireFile(source);
        requireFile(destination);
        if (!source.startsWith(PhotoStoragePaths.STAGING + "/")
                || destination.startsWith(PhotoStoragePaths.STAGING + "/")) throw unavailable();
        mkdir(destination.substring(0, destination.lastIndexOf('/')));
        try (Response response = request(endpoint(destination, "GETFILESTATUS", ""), "GET", null)) {
            if (response.code() != 404) throw unavailable();
        }
        boolRequest(endpoint(source, "RENAME", "&destination=" + encode(destination)), "PUT");
    }

    @Override
    public InputStream open(String path) {
        requireFile(path);
        URI target = redirect(endpoint(path, "OPEN", ""), "GET", path, "OPEN");
        Response response = request(target, "GET", null);
        if (response.code() != 200) {
            response.close();
            throw unavailable();
        }
        try {
            return new FilterInputStream(response.connection.getInputStream()) {
                @Override
                public int read() {
                    try {
                        return in.read();
                    } catch (IOException exception) {
                        throw unavailable();
                    }
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    try {
                        return in.read(bytes, offset, length);
                    } catch (IOException exception) {
                        throw unavailable();
                    }
                }

                @Override
                public void close() {
                    response.close();
                }
            };
        } catch (IOException exception) {
            response.close();
            throw unavailable();
        }
    }

    @Override
    public void delete(String path) {
        requireFile(path);
        try (Response response =
                request(endpoint(path, "DELETE", "&recursive=false"), "DELETE", null)) {
            if (response.code() != 200 && response.code() != 404) throw unavailable();
            if (response.code() == 200) json(response);
        }
    }

    @Override
    public List<Entry> list(String directory) {
        if (!PhotoStoragePaths.directory(directory)) throw unavailable();
        try (Response response = request(endpoint(directory, "LISTSTATUS", ""), "GET", null)) {
            if (response.code() == 404) return List.of();
            if (response.code() != 200) throw unavailable();
            JsonNode statuses = json(response).path("FileStatuses").path("FileStatus");
            if (!statuses.isArray()) throw unavailable();
            List<Entry> entries = new ArrayList<>();
            for (JsonNode status : statuses) {
                String suffix = status.path("pathSuffix").asString("");
                String type = status.path("type").asString("");
                String path = directory + "/" + suffix;
                if (suffix.contains("/") || suffix.isEmpty()) continue;
                boolean isDirectory = "DIRECTORY".equals(type);
                if (!(isDirectory
                        ? PhotoStoragePaths.directory(path)
                        : "FILE".equals(type) && PhotoStoragePaths.file(path))) continue;
                JsonNode modified = status.path("modificationTime");
                if (!modified.isIntegralNumber() || modified.asLong() < 0) continue;
                entries.add(new Entry(path, isDirectory, Instant.ofEpochMilli(modified.asLong())));
            }
            return List.copyOf(entries);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private void mkdir(String directory) {
        boolRequest(endpoint(directory, "MKDIRS", "&permission=750"), "PUT");
    }

    private void boolRequest(URI uri, String method) {
        try (Response response = request(uri, method, null)) {
            if (response.code() != 200 || !json(response).path("boolean").asBoolean(false))
                throw unavailable();
        }
    }

    private URI redirect(URI endpoint, String method, String path, String operation) {
        try (Response response = request(endpoint, method, null)) {
            if (response.code() != 307) throw unavailable();
            URI target = URI.create(response.connection.getHeaderField("Location"));
            URI targetOrigin = URI.create(target.getScheme() + "://" + target.getRawAuthority());
            if (target.getRawUserInfo() != null
                    || target.getRawFragment() != null
                    || !dataNodes.contains(targetOrigin)
                    || !("/webhdfs/v1" + path).equals(target.getRawPath())) throw unavailable();
            Map<String, String> query = new HashMap<>();
            for (String pair : Objects.requireNonNull(target.getRawQuery()).split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2
                        || query.putIfAbsent(
                                        URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                                                .toLowerCase(Locale.ROOT),
                                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                                != null) throw unavailable();
            }
            if (!operation.equals(query.get("op")) || !user.equals(query.get("user.name")))
                throw unavailable();
            if (operation.equals("CREATE")
                    && !("false".equals(query.get("overwrite"))
                            && "2".equals(query.get("replication"))
                            && "600".equals(query.get("permission")))) throw unavailable();
            if (query.containsKey("doas") || query.containsKey("delegation")) throw unavailable();
            return target;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private URI endpoint(String path, String operation, String extra) {
        return URI.create(
                nameNode
                        + "/webhdfs/v1"
                        + path
                        + "?op="
                        + operation
                        + "&user.name="
                        + encode(user)
                        + extra);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode json(Response response) {
        try (InputStream stream = response.connection.getInputStream()) {
            byte[] bytes = stream.readNBytes(JSON_LIMIT + 1);
            if (bytes.length > JSON_LIMIT) throw unavailable();
            return mapper.readTree(bytes);
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    private Response request(URI uri, String method, byte[] bytes) {
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod(method);
            HttpURLConnection current = connection;
            deadline = DEADLINES.schedule(current::disconnect, 15, TimeUnit.SECONDS);
            if ("PUT".equals(method)) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes == null ? 0 : bytes.length);
                connection.setRequestProperty("Content-Type", "application/octet-stream");
                try (OutputStream stream = connection.getOutputStream()) {
                    if (bytes != null) stream.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            return new Response(connection, deadline, code);
        } catch (IOException | RuntimeException exception) {
            if (deadline != null) deadline.cancel(false);
            if (connection != null) connection.disconnect();
            throw unavailable();
        }
    }

    private static void requireFile(String path) {
        if (!PhotoStoragePaths.file(path)) throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
    }

    private record Response(HttpURLConnection connection, ScheduledFuture<?> deadline, int code)
            implements AutoCloseable {
        @Override
        public void close() {
            deadline.cancel(false);
            connection.disconnect();
        }
    }
}
