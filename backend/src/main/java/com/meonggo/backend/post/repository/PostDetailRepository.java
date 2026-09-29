package com.meonggo.backend.post.repository;

import com.meonggo.backend.post.dto.PostDetailResponse.LostReportResponse;
import com.meonggo.backend.post.dto.PostDetailResponse.PhotoResponse;
import com.meonggo.backend.post.dto.PostDetailResponse.ShelterResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostDetailRepository {
    private final JdbcTemplate jdbc;

    public PostDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PostProjection> find(long postId) {
        return jdbc
                .query(
                        """
                SELECT a.id, a.case_type, a.source_type, a.status, a.version, a.name,
                       a.species, a.breed_name, a.sex, a.color, a.event_date, a.event_time,
                       a.feature_text, a.created_at, a.updated_at, m.id AS member_id, m.nickname,
                       s.name AS shelter_name, s.phone AS shelter_phone, s.address,
                       s.jurisdiction, sa.notice_no, sa.notice_start_date, sa.notice_end_date,
                       sa.process_state_raw,
                       lr.org_name, lr.happen_place, lr.rfid_code, lr.first_seen_date, lr.last_seen_date
                FROM animal_case a
                LEFT JOIN user_post u ON a.source_type='USER' AND u.animal_case_id=a.id
                LEFT JOIN member m ON m.id=u.member_id AND m.status='ACTIVE' AND m.deleted_at IS NULL
                LEFT JOIN shelter_animal sa ON a.source_type='PUBLIC' AND sa.animal_case_id=a.id
                LEFT JOIN shelter s ON s.id=sa.shelter_id
                LEFT JOIN lost_report lr ON a.source_type='PUBLIC' AND lr.animal_case_id=a.id
                WHERE a.id=? AND a.status IN ('ACTIVE','CLOSED') AND a.deleted_at IS NULL
                  AND ((a.source_type='USER' AND m.id IS NOT NULL
                        AND (a.status='ACTIVE' OR a.closed_at + interval '90 days' > CURRENT_TIMESTAMP))
                       OR (a.source_type='PUBLIC' AND (s.id IS NOT NULL OR lr.animal_case_id IS NOT NULL)))
                """,
                        (rs, row) ->
                                new PostProjection(
                                        rs.getLong("id"),
                                        rs.getString("case_type"),
                                        rs.getString("source_type"),
                                        rs.getString("status"),
                                        rs.getLong("version"),
                                        rs.getString("name"),
                                        rs.getString("species"),
                                        rs.getString("breed_name"),
                                        rs.getString("sex"),
                                        rs.getString("color"),
                                        rs.getObject("event_date", LocalDate.class),
                                        rs.getObject("event_time", LocalTime.class),
                                        rs.getString("feature_text"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("updated_at").toInstant(),
                                        rs.getObject("member_id", Long.class),
                                        rs.getString("nickname"),
                                        new ShelterResponse(
                                                rs.getString("shelter_name"),
                                                rs.getString("shelter_phone"),
                                                rs.getString("address"),
                                                rs.getString("jurisdiction"),
                                                rs.getString("notice_no"),
                                                rs.getObject("notice_start_date", LocalDate.class),
                                                rs.getObject("notice_end_date", LocalDate.class),
                                                rs.getString("process_state_raw")),
                                        rs.getObject("first_seen_date") == null
                                                ? null
                                                : new LostReportResponse(
                                                        rs.getString("org_name"),
                                                        rs.getString("happen_place"),
                                                        rs.getString("rfid_code"),
                                                        rs.getObject(
                                                                "first_seen_date", LocalDate.class),
                                                        rs.getObject(
                                                                "last_seen_date", LocalDate.class),
                                                        LostReportResponse.CONTACT_NOTICE,
                                                        null)),
                        postId)
                .stream()
                .findFirst();
    }

    public List<LocationProjection> locations(long postId, boolean owner, boolean activeUser) {
        // 공개 대상이 아닌 암호문과 좌표는 JDBC projection에 올리지 않는다.
        return jdbc.query(
                """
                SELECT location_type, region_code, emd_code, public_location,
                       CASE WHEN ? OR (? AND exact_location_visible
                            AND disclosure_policy_version='exact-location-v1'
                            AND disclosure_consented_at IS NOT NULL)
                            THEN exact_location_ciphertext END AS permitted_ciphertext,
                       CASE WHEN ? THEN exact_location_visible END AS owner_visibility
                FROM animal_case_location WHERE animal_case_id=?
                """,
                (rs, row) ->
                        new LocationProjection(
                                rs.getString("location_type"),
                                rs.getString("region_code"),
                                rs.getString("emd_code"),
                                rs.getString("public_location"),
                                rs.getString("permitted_ciphertext"),
                                rs.getObject("owner_visibility", Boolean.class)),
                owner,
                activeUser,
                owner,
                postId);
    }

    public List<PhotoResponse> photos(long postId, boolean user) {
        // 사용자 사진의 내부 저장 경로를 조회하거나 역직렬화하지 않는다.
        return jdbc.query(
                """
                SELECT id, sort_order,
                       CASE WHEN storage_type='USER_UPLOAD' THEN '/api/v1/photos/' || id
                            ELSE storage_uri END AS url
                FROM animal_photo WHERE animal_case_id=? AND storage_type=?
                ORDER BY sort_order, id
                """,
                (rs, row) ->
                        new PhotoResponse(
                                rs.getLong("id"), rs.getString("url"), rs.getInt("sort_order")),
                postId,
                user ? "USER_UPLOAD" : "PUBLIC_URL");
    }

    public record PostProjection(
            long id,
            String type,
            String source,
            String status,
            long version,
            String name,
            String species,
            String breedName,
            String sex,
            String color,
            LocalDate eventDate,
            LocalTime eventTime,
            String featureText,
            Instant createdAt,
            Instant updatedAt,
            Long memberId,
            String nickname,
            ShelterResponse shelter,
            LostReportResponse lostReport) {}

    public record LocationProjection(
            String role,
            String regionCode,
            String emdCode,
            String publicLocation,
            String permittedCiphertext,
            Boolean ownerVisibility) {
        @Override
        public String toString() {
            return "LocationProjection[redacted]";
        }
    }
}
