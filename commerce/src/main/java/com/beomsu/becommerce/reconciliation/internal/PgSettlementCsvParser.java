package com.beomsu.becommerce.reconciliation.internal;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PG 정산 파일(CSV) 파서 — 외부 기록({@link ExternalRecord}) 인입 경로.
 *
 * <p>대사 엔진은 내부 기록 vs 외부 기록을 대조하지만, 실제 PG 정산 파일을 {@code ExternalRecord}로
 * 만드는 경로가 없어 사실상 트리거되지 않았다. 이 파서가 그 구멍을 메운다.
 *
 * <p><b>헤더 기반</b>이라 컬럼 순서·부가 컬럼(거래일시·승인번호 등)에 영향받지 않는다. 첫 줄에서
 * orderNo/amount 컬럼의 위치를 컬럼명으로 찾고(대소문자·공백 무시, 한글/스네이크 별칭 허용),
 * 두 컬럼이 없으면 {@code INVALID_SETTLEMENT_FILE}로 거부한다.
 *
 * <p><b>견고성</b>: 정산 파일에는 요약행·공백행·깨진 행이 섞일 수 있다. 개별 데이터행이 불량이어도
 * (orderNo 빈 값 / amount 파싱 실패) 전체를 깨지 않고 그 행만 건너뛰며 스킵 수를 집계한다.
 * CSV는 단순 콤마 분리로 처리한다(orderNo/amount에는 콤마가 흔치 않음 — 금액의 천단위 콤마는 제거).
 */
@Component
public class PgSettlementCsvParser {

    /** orderNo 컬럼 별칭 — 정규화(소문자·공백제거) 후 비교. */
    private static final Set<String> ORDER_NO_ALIASES = Set.of("orderno", "order_no", "주문번호");
    /** amount 컬럼 별칭 — 정규화 후 비교. */
    private static final Set<String> AMOUNT_ALIASES = Set.of("amount", "settle_amount", "settleamount", "결제금액");
    /**
     * PG의 행 단위 거래 식별자 별칭. <b>선택 컬럼</b>이다 — 없어도 파싱은 되지만,
     * 없으면 같은 거래가 중복 기록된 것을 가려낼 수 없다(ExternalRecord 참고).
     *
     * <p><b>별칭을 좁게 잡았다.</b> 여기 넣은 것은 주요 PG가 <b>행 단위 고유</b>라고 문서에서
     * 밝힌 것들뿐이다. {@code 승인번호} 같은 컬럼은 넣지 않았다 — 승인번호는 <b>원거래와 환불이
     * 공유할 수 있어</b> 행 단위 고유가 아니다. 그걸 식별자로 쓰면 정상적인 환불 행을
     * 중복으로 오판해 <b>버린다</b>. 잘못 버리는 것이 못 잡는 것보다 나쁘다.
     */
    private static final Set<String> TRANSACTION_ID_ALIASES = Set.of(
            "transactionid", "transaction_id", "pspreference", "psp_reference",
            "balancetransactionid", "balance_transaction_id");

    /**
     * 파싱 결과 — 유효한 외부 기록 목록과 건너뛴(불량) 행 수.
     *
     * @param records 매칭 엔진에 넘길 외부 기록
     * @param skipped orderNo 빈 값 또는 amount 파싱 실패로 건너뛴 데이터행 수(요약행 등 방어)
     * @param skippedRows 건너뛴 행의 <b>내역</b>. 수만 세면 요약행 3개와
     *                    금액 50만원짜리 거래 3건을 구분할 수 없다
     */
    public record ParseResult(List<ExternalRecord> records, int skipped,
                              List<SkippedRow> skippedRows) {

        /**
         * 건너뛴 행 하나.
         *
         * @param line   파일 내 줄 번호. 사람이 원본을 찾아볼 수 있어야 한다
         * @param reason 왜 건너뛰었나
         * @param amount 그 행에서 읽을 수 있었던 금액. <b>null 이 아니면 돈이 걸린 행</b>이다
         */
        public record SkippedRow(int line, String reason, Long amount) {
        }

        /** 금액이 있는데 건너뛴 행 — <b>요약행 노이즈가 아니라 조사 대상이다.</b> */
        public List<SkippedRow> moneyBearing() {
            return skippedRows.stream().filter(r -> r.amount() != null && r.amount() != 0).toList();
        }
    }

    /**
     * 정산 CSV를 파싱해 외부 기록으로 변환한다. 스트림은 이 메서드가 닫는다.
     *
     * @throws ReconciliationException 파일이 비었거나 필수 컬럼(orderNo/amount)이 없으면 {@code INVALID_SETTLEMENT_FILE}
     */
    public ParseResult parse(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = readNextNonBlank(reader);
            if (headerLine == null) {
                throw new ReconciliationException("INVALID_SETTLEMENT_FILE", "정산 파일이 비어 있습니다.");
            }
            String[] header = stripBom(headerLine).split(",", -1);
            int orderNoIdx = findColumn(header, ORDER_NO_ALIASES);
            int amountIdx = findColumn(header, AMOUNT_ALIASES);
            // 선택 컬럼 — 없으면 -1이고, 그 경우 중복 감지를 포기한다(파싱은 계속한다).
            int txIdIdx = findColumn(header, TRANSACTION_ID_ALIASES);
            if (orderNoIdx < 0 || amountIdx < 0) {
                throw new ReconciliationException("INVALID_SETTLEMENT_FILE",
                        "정산 파일에 필수 컬럼(orderNo, amount)이 없습니다. 헤더: " + headerLine);
            }
            int maxIdx = Math.max(orderNoIdx, amountIdx);

            List<ExternalRecord> records = new ArrayList<>();
            List<ParseResult.SkippedRow> skippedRows = new ArrayList<>();
            int lineNo = 1;                    // 헤더가 1행
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue; // 공백행은 노이즈로 무시(스킵 카운트에 넣지 않음)
                }
                String[] cols = line.split(",", -1);
                if (cols.length <= maxIdx) {
                    skippedRows.add(new ParseResult.SkippedRow(
                            lineNo, "컬럼 수 부족(요약행 등)", null));
                    continue;
                }
                String orderNo = cols[orderNoIdx].trim();
                if (orderNo.isEmpty()) {
                    // 금액은 읽어 둔다. 주문번호만 없고 <돈은 있는> 행이면 조사 대상이다.
                    skippedRows.add(new ParseResult.SkippedRow(
                            lineNo, "주문번호 없음", parseAmount(cols[amountIdx])));
                    continue;
                }
                Long amount = parseAmount(cols[amountIdx]);
                if (amount == null) {
                    skippedRows.add(new ParseResult.SkippedRow(
                            lineNo, "금액 해석 불가: " + cols[amountIdx].trim(), null));
                    continue;
                }
                String transactionId = (txIdIdx >= 0 && txIdIdx < cols.length)
                        ? blankToNull(cols[txIdIdx].trim()) : null;
                records.add(new ExternalRecord(orderNo, amount, transactionId));
            }
            return new ParseResult(records, skippedRows.size(), List.copyOf(skippedRows));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 다음 비공백 줄을 읽는다(파일 앞머리의 빈 줄을 건너뛰고 헤더를 찾는다). */
    private static String readNextNonBlank(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    /** 헤더 컬럼명을 정규화해 별칭 집합과 일치하는 첫 컬럼 인덱스를 찾는다. 없으면 -1. */
    private static int findColumn(String[] header, Set<String> aliases) {
        for (int i = 0; i < header.length; i++) {
            if (aliases.contains(normalize(header[i]))) {
                return i;
            }
        }
        return -1;
    }

    /** 대소문자·양끝공백·내부공백을 무시한 정규화(컬럼명 비교용). */
    private static String normalize(String raw) {
        return raw.trim().toLowerCase().replaceAll("\\s", "");
    }

    /**
     * 금액 토큰 파싱 — 천단위 콤마·공백 제거 후 long. 실패하면 null(스킵 신호).
     *
     * <p><b>음수를 받는다.</b> 원래는 음수를 버렸는데, 그건 PG 파일의 모양과 맞지 않았다 —
     * <b>환불·챠지백은 음수 행으로 온다</b>({@link ExternalRecord} 계약).
     * ADR-013 에서 내부 기록도 취소를 음수 행으로 쌓게 바꿨으므로, 파서가 음수를 버리면
     * 환불이 있는 날마다 <b>내부에만 있음</b>으로 잡힌다 — 정상 환불이 예외 큐를 채운다.
     *
     * <p>재현으로 확인했다: 승인 10,000 / 환불 -3,000 두 행짜리 파일에서 환불이 사라졌다.
     */
    private static Long parseAmount(String raw) {
        String cleaned = raw.trim().replace(",", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** UTF-8 BOM이 앞에 붙은 파일(엑셀 저장물)의 헤더 첫 컬럼명이 깨지지 않게 제거. */
    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }

    /** 빈 문자열은 "식별자 없음"과 같다 — 빈 값끼리 중복으로 오판하지 않게 null로 만든다. */
    private static String blankToNull(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }
}
