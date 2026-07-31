package com.groupdrop.user;

/**
 * 역할 4종 (기획서 16.3). users.role CHECK 제약과 값 집합이 일치해야 한다 (V1__base_domain.sql).
 */
public enum UserRole {
    BUYER,
    INFLUENCER,
    SUPPLIER,
    ADMIN
}
