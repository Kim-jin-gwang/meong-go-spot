package com.meonggo.backend.member.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 탈퇴 후 30일이 지난 회원의 개인정보와 관계형 데이터를 단계적으로 파기한다. */
@Component
public class MemberDataErasureJob {
    private static final Logger LOG = LoggerFactory.getLogger(MemberDataErasureJob.class);
    static final String ADVISORY_LOCK_NAME = "member_data_erasure_job";
    private final DataSource dataSource;
    private final MemberPersonalDataErasureService personalData;
    private final MemberRelationalDataErasureService relationalData;

    public MemberDataErasureJob(
            DataSource dataSource,
            MemberPersonalDataErasureService personalData,
            MemberRelationalDataErasureService relationalData) {
        this.dataSource = dataSource;
        this.personalData = personalData;
        this.relationalData = relationalData;
    }

    @Scheduled(cron = "${MEMBER_DATA_ERASURE_CRON:0 30 3 * * *}", zone = "Asia/Seoul")
    public void run() {
        runOnce();
    }

    void runOnce() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) return;
            try {
                try {
                    personalData.eraseDueMembers();
                } catch (RuntimeException exception) {
                    LOG.warn("Member data erasure deferred: phase=batch code=DATABASE_UNAVAILABLE");
                }
                try {
                    relationalData.eraseDueMembers();
                } catch (RuntimeException exception) {
                    LOG.warn(
                            "Member data erasure deferred: phase=relational-batch "
                                    + "code=DATABASE_UNAVAILABLE");
                }
            } finally {
                unlock(connection);
            }
        } catch (SQLException exception) {
            LOG.warn("Member data erasure deferred: phase=job-lock code=DATABASE_UNAVAILABLE");
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, ADVISORY_LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_advisory_unlock(hashtext(?))")) {
            statement.setString(1, ADVISORY_LOCK_NAME);
            statement.execute();
        } catch (SQLException exception) {
            LOG.warn("Member data erasure lock release deferred: code=DATABASE_UNAVAILABLE");
        }
    }
}
