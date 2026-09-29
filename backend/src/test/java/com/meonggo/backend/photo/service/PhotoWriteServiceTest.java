package com.meonggo.backend.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.entity.AnimalPhoto;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import com.meonggo.backend.photo.image.NormalizedPhoto;
import com.meonggo.backend.photo.storage.PhotoStorage;
import com.meonggo.backend.post.service.PostAggregateService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@ActiveProfiles("test")
class PhotoWriteServiceTest {
    @MockitoBean private PhotoStorage configuredStorage;
    @Autowired private PostAggregateService ids;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private final MemoryStorage storage = new MemoryStorage();
    private PhotoWriteService service;
    private long postId;
    private String previousPath;

    @BeforeEach
    void setup() {
        service = new PhotoWriteService(storage, ids, jdbc, transactions);
        postId =
                jdbc.queryForObject(
                        """
                insert into animal_case(case_type,source_type,status,listed_at,species,sex,
                  event_date,created_at,updated_at)
                values('LOST','USER','ACTIVE',now(),'DOG','UNKNOWN',current_date,now(),now()) returning id
                """,
                        Long.class);
        long oldId = ids.reservePhotoId();
        previousPath = "/data/user/images/" + postId + "/" + oldId + ".jpg";
        insert(
                AnimalPhoto.upload(
                        oldId, postId, 0, new byte[] {9}, 512, 512, "a".repeat(64), Instant.now()));
        storage.files.put(previousPath, new byte[] {9});
    }

    @Test
    void publishesWholeOrderedSetAndRemovesOldOnlyAfterCommit() {
        var response =
                service.write(
                        postId,
                        photos(),
                        replacements -> {
                            assertThat(storage.files).containsKey(previousPath);
                            replacements.forEach(
                                    photo ->
                                            assertThat(storage.files)
                                                    .containsKey(photo.getStorageUri()));
                            replace(replacements);
                            return "saved";
                        });
        assertThat(response).isEqualTo("saved");
        assertThat(storage.files).doesNotContainKey(previousPath);
        assertThat(storage.files).hasSize(2);
        assertThat(
                        jdbc.queryForList(
                                "select sort_order from animal_photo where animal_case_id=? order by sort_order",
                                Integer.class,
                                postId))
                .containsExactly(0, 1);
    }

    @Test
    void failedWriteOrRenameKeepsPreviousSetAndCleansPartialFiles() {
        for (String failure : List.of("write", "move")) {
            storage.failAt = failure;
            assertThatThrownBy(
                            () ->
                                    service.write(
                                            postId,
                                            photos(),
                                            replacement -> {
                                                replace(replacement);
                                                return "unexpected";
                                            }))
                    .isInstanceOf(BusinessException.class);
            assertPreviousSet();
        }
    }

    @Test
    void databaseRollbackKeepsOldRowsAndFiles() {
        assertThatThrownBy(
                        () ->
                                service.write(
                                        postId,
                                        photos(),
                                        replacements -> {
                                            replace(replacements);
                                            jdbc.update(
                                                    "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values (?, -1, gen_random_uuid(), repeat('a',64))",
                                                    postId);
                                            return "unexpected";
                                        }))
                .isInstanceOf(BusinessException.class);
        assertPreviousSet();
    }

    @Test
    void failedOldFileDeletionDoesNotUndoCommittedResult() {
        storage.failAt = "delete";
        service.write(
                postId,
                photos(),
                replacements -> {
                    replace(replacements);
                    return "saved";
                });
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from animal_photo where animal_case_id=?",
                                Integer.class,
                                postId))
                .isEqualTo(2);
        assertThat(storage.files).containsKey(previousPath);
    }

    @Test
    void noMetadataPublicationCannotCommitOrLeakNewFiles() {
        assertThatThrownBy(() -> service.write(postId, photos(), replacements -> "forgot metadata"))
                .isInstanceOf(BusinessException.class);
        assertPreviousSet();
    }

    @Test
    void unknownCommitOutcomeDefersFinalFileCleanupEvenWhenRowsAreNotVisible() {
        var unknown =
                new PlatformTransactionManager() {
                    @Override
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return transactions.getTransaction(definition);
                    }

                    @Override
                    public void commit(TransactionStatus status) {
                        var synchronizations =
                                TransactionSynchronizationManager.getSynchronizations();
                        // 통신 유실 시 호출자가 결과를 알 수 없는 상황을 재현한다.
                        transactions.rollback(status);
                        synchronizations.forEach(
                                sync ->
                                        sync.afterCompletion(
                                                TransactionSynchronization.STATUS_UNKNOWN));
                        throw new TransactionSystemException("simulated unknown commit outcome");
                    }

                    @Override
                    public void rollback(TransactionStatus status) {
                        transactions.rollback(status);
                    }
                };
        service = new PhotoWriteService(storage, ids, jdbc, unknown);
        assertThatThrownBy(
                        () ->
                                service.write(
                                        postId,
                                        photos(),
                                        replacements -> {
                                            replace(replacements);
                                            return "saved";
                                        }))
                .isInstanceOf(BusinessException.class);
        assertThat(storage.files).hasSize(3).containsKey(previousPath);
        assertThat(
                        jdbc.queryForList(
                                "select storage_uri from animal_photo where animal_case_id=?",
                                String.class,
                                postId))
                .containsExactly(previousPath);
    }

    private void assertPreviousSet() {
        assertThat(storage.files).containsOnlyKeys(previousPath);
        assertThat(
                        jdbc.queryForList(
                                "select storage_uri from animal_photo where animal_case_id=?",
                                String.class,
                                postId))
                .containsExactly(previousPath);
    }

    private List<NormalizedPhoto> photos() {
        return List.of(
                new NormalizedPhoto(new byte[] {1}, 512, 512, "b".repeat(64)),
                new NormalizedPhoto(new byte[] {2}, 512, 512, "c".repeat(64)));
    }

    private void replace(List<AnimalPhoto> replacements) {
        jdbc.update("delete from animal_photo where animal_case_id=?", postId);
        replacements.forEach(this::insert);
    }

    private void insert(AnimalPhoto p) {
        jdbc.update(
                """
                insert into animal_photo(id,animal_case_id,storage_type,storage_uri,content_type,
                  byte_size,width_px,height_px,sort_order,checksum_sha256,created_at)
                values(?,?,'USER_UPLOAD',?,'image/jpeg',?,512,512,?,?,now())
                """,
                p.getId(),
                postId,
                p.getStorageUri(),
                p.getByteSize(),
                p.getSortOrder(),
                p.getChecksumSha256());
    }

    private static class MemoryStorage implements PhotoStorage {
        final Map<String, byte[]> files = new HashMap<>();
        String failAt;
        int writes;

        @Override
        public void write(String path, byte[] bytes) {
            files.put(path, bytes.clone());
            if ("write".equals(failAt))
                throw new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
        }

        @Override
        public void move(String source, String destination) {
            if ("move".equals(failAt))
                throw new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
            files.put(destination, files.remove(source));
        }

        @Override
        public void delete(String path) {
            if ("delete".equals(failAt))
                throw new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
            files.remove(path);
        }

        @Override
        public InputStream open(String path) {
            return new ByteArrayInputStream(files.get(path));
        }

        @Override
        public List<Entry> list(String directory) {
            return List.of();
        }
    }
}
