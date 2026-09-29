package com.meonggo.backend.global.common.entity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditedItemRepository extends JpaRepository<AuditedItem, Long> {}
