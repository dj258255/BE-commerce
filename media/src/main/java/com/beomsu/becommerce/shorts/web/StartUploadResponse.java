package com.beomsu.becommerce.shorts.web;

import com.beomsu.becommerce.shorts.ShortsService;

/** 업로드 시작 응답 — 발급된 숏폼 id와 업로드 대상 URL. */
public record StartUploadResponse(Long shortVideoId, String uploadUrl) {

    public static StartUploadResponse from(ShortsService.StartUploadResult result) {
        return new StartUploadResponse(result.shortVideoId(), result.uploadUrl());
    }
}
