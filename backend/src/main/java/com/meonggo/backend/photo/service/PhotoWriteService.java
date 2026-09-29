package com.meonggo.backend.photo.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.photo.entity.AnimalPhoto;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import com.meonggo.backend.photo.image.NormalizedPhoto;
import com.meonggo.backend.photo.storage.PhotoStorage;
import com.meonggo.backend.post.service.PostAggregateService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** P3/P5는 소유권·멱등키·버전을 확인한 DB 변경 함수를 전달한다. 외부 트랜잭션 없이 호출한다. */
@Service
public class PhotoWriteService {
    private static final Logger LOG = LoggerFactory.getLogger(PhotoWriteService.class);
    private final PhotoStorage storage;
    private final PostAggregateService ids;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PhotoWriteService(
            PhotoStorage storage,
            PostAggregateService ids,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        this.storage = storage;
        this.ids = ids;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(30);
    }

    public <T> T write(
            long postId,
            List<NormalizedPhoto> photos,
            Function<List<AnimalPhoto>, T> databaseWrite) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Photo publication requires an independent boundary");
        }
        if (postId <= 0 || databaseWrite == null)
            throw new IllegalArgumentException("Invalid photo publication");
        if (photos == null || photos.isEmpty() || photos.size() > 10)
            throw new BusinessException(PhotoErrorCode.INVALID_COUNT);
        String requestId = UUID.randomUUID().toString();
        List<String> staging = new ArrayList<>();
        List<AnimalPhoto> prepared = new ArrayList<>();
        List<String> previous = new ArrayList<>();
        AtomicInteger completion = new AtomicInteger(-1);
        try {
            for (int index = 0; index < photos.size(); index++) {
                var input = photos.get(index);
                byte[] bytes = input.bytes();
                var photo =
                        AnimalPhoto.upload(
                                ids.reservePhotoId(),
                                postId,
                                index,
                                bytes,
                                input.width(),
                                input.height(),
                                input.checksum(),
                                Instant.now());
                String temporary =
                        "/data/user/images/.staging/" + requestId + "/" + photo.getId() + ".jpg";
                staging.add(temporary);
                prepared.add(photo);
                storage.write(temporary, bytes);
                storage.move(temporary, photo.getStorageUri());
            }
            T result =
                    transaction.execute(
                            status -> {
                                completion.set(TransactionSynchronization.STATUS_UNKNOWN);
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCompletion(int outcome) {
                                                completion.set(outcome);
                                            }
                                        });
                                previous.addAll(
                                        jdbc.queryForList(
                                                "select storage_uri from animal_photo where animal_case_id=? and storage_type='USER_UPLOAD'",
                                                String.class,
                                                postId));
                                T response = databaseWrite.apply(List.copyOf(prepared));
                                List<Long> persisted =
                                        jdbc.queryForList(
                                                "select id from animal_photo where animal_case_id=? order by sort_order",
                                                Long.class,
                                                postId);
                                if (!persisted.equals(
                                        prepared.stream().map(AnimalPhoto::getId).toList())) {
                                    throw new BusinessException(
                                            CommonErrorCode.INTERNAL_SERVER_ERROR);
                                }
                                return response;
                            });
            previous.forEach(this::deleteUnreferenced);
            return result;
        } catch (BusinessException ex) {
            compensate(prepared, completion.get());
            throw ex;
        } catch (RuntimeException ex) {
            compensate(prepared, completion.get());
            // SQL commit 실패 원문에는 내부 경로가 들어갈 수 있다.
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        } finally {
            staging.forEach(this::deleteSafely);
        }
    }

    private void compensate(List<AnimalPhoto> prepared, int completion) {
        if (completion == -1 || completion == TransactionSynchronization.STATUS_ROLLED_BACK) {
            prepared.forEach(photo -> deleteUnreferenced(photo.getStorageUri()));
        } else {
            // UNKNOWN에서 지금 행이 안 보여도 원래 COMMIT이 나중에 성공할 수 있다.
            LOG.warn("Photo cleanup deferred: transaction completion uncertain");
        }
    }

    private void deleteUnreferenced(String path) {
        try {
            // commit 응답이 유실됐거나 DB 상태가 불명확할 때 참조된 파일을 회수하지 않는다.
            boolean referenced =
                    Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "select exists(select 1 from animal_photo where storage_uri=?)",
                                    Boolean.class,
                                    path));
            if (!referenced) deleteSafely(path);
        } catch (RuntimeException ex) {
            LOG.warn("Photo cleanup deferred: database check unavailable");
        }
    }

    private void deleteSafely(String path) {
        try {
            storage.delete(path);
        } catch (RuntimeException ex) {
            LOG.warn("Photo cleanup deferred: storage unavailable");
        }
    }
}
