package com.groupdrop.user;

public record UserSummaryResponse(Long id, String email, String displayName, UserRole role) {

    static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }
}
