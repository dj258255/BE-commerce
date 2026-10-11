package com.beomsu.becommerce.shorts.web;

/** 업로드 시작 요청 — 실제 파일을 보내기 전에 클라이언트가 보고하는 메타(R21). */
public record StartUploadRequest(
        int durationSeconds,
        long fileSizeBytes,
        int width,
        int height,
        String contentType) {
}
