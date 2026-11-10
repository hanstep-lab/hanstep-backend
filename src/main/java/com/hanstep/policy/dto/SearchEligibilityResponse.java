package com.hanstep.policy.dto;

import java.util.List;

/** AI-R1 응답. 없는 ID 는 목록에서 빠진다. */
public record SearchEligibilityResponse(List<SearchEligibilityItem> documents) {
}
