package com.meonggo.backend.post.repository;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.dto.UpdatePostResponse;
import com.meonggo.backend.post.entity.AnimalDetails;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import com.meonggo.backend.post.exception.PostErrorCode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostMetadataRepository {
    private final JdbcTemplate jdbc;

    public PostMetadataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Content content(long id) {
        return jdbc.queryForObject(
                """
                select name,species,breed_name,sex,color,event_date,event_time,feature_text,updated_at
                from animal_case where id=?
                """,
                (row, number) ->
                        new Content(
                                new AnimalDetails(
                                        row.getString("name"),
                                        Species.valueOf(row.getString("species")),
                                        row.getString("breed_name"),
                                        Sex.valueOf(row.getString("sex")),
                                        row.getString("color"),
                                        row.getObject("event_date", LocalDate.class),
                                        row.getObject("event_time", LocalTime.class),
                                        row.getString("feature_text")),
                                row.getTimestamp("updated_at").toInstant()),
                id);
    }

    public List<Location> locations(long id) {
        return jdbc.query(
                """
                select location_type,region_code,emd_code,public_location,exact_location_ciphertext,
                       latitude,longitude,exact_location_visible,disclosure_policy_version,disclosure_consented_at
                from animal_case_location where animal_case_id=? order by location_type
                """,
                (row, number) ->
                        new Location(
                                row.getString("location_type"),
                                row.getString("region_code"),
                                row.getString("emd_code"),
                                row.getString("public_location"),
                                row.getString("exact_location_ciphertext"),
                                row.getBigDecimal("latitude"),
                                row.getBigDecimal("longitude"),
                                row.getBoolean("exact_location_visible"),
                                row.getString("disclosure_policy_version"),
                                row.getTimestamp("disclosure_consented_at") == null
                                        ? null
                                        : row.getTimestamp("disclosure_consented_at").toInstant()),
                id);
    }

    public UpdatePostResponse update(
            long id, long version, long nextVersion, AnimalDetails details) {
        var responses =
                jdbc.query(
                        """
                update animal_case set name=?,species=?,breed_name=?,sex=?,color=?,event_date=?,
                event_time=?,feature_text=?,version=?,updated_at=clock_timestamp()
                where id=? and version=? returning updated_at
                """,
                        (row, number) ->
                                new UpdatePostResponse(
                                        id,
                                        nextVersion,
                                        row.getTimestamp("updated_at").toInstant()),
                        details.name(),
                        details.species().name(),
                        details.breedName(),
                        details.sex().name(),
                        details.color(),
                        details.eventDate(),
                        details.eventTime(),
                        details.featureText(),
                        nextVersion,
                        id,
                        version);
        if (responses.size() != 1) throw new BusinessException(PostErrorCode.VERSION_CONFLICT);
        return responses.getFirst();
    }

    public void updateLocation(long id, Location value) {
        int updated =
                jdbc.update(
                        """
                update animal_case_location set region_code=?,emd_code=?,public_location=?,exact_location_ciphertext=?,
                latitude=?,longitude=?,exact_location_visible=?,disclosure_policy_version=?,disclosure_consented_at=?
                where animal_case_id=? and location_type=?
                """,
                        value.region(),
                        value.emd(),
                        value.display(),
                        value.ciphertext(),
                        value.latitude(),
                        value.longitude(),
                        value.visible(),
                        value.policy(),
                        value.consentedAt() == null ? null : Timestamp.from(value.consentedAt()),
                        id,
                        value.role());
        if (updated != 1) throw new BusinessException(PostErrorCode.NOT_FOUND);
    }

    public record Content(AnimalDetails details, Instant updatedAt) {
        @Override
        public String toString() {
            return "Content[redacted]";
        }
    }

    public record Location(
            String role,
            String region,
            String emd,
            String display,
            String ciphertext,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean visible,
            String policy,
            Instant consentedAt) {
        @Override
        public String toString() {
            return "Location[redacted]";
        }
    }
}
